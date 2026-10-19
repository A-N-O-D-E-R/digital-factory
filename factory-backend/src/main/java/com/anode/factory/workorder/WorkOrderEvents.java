package com.anode.factory.workorder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The timeline of work orders (written by database triggers, see changelog 014) and its live
 * streams. Streams send each event once every transaction that started before it has ended, so
 * that none is skipped; the event id is the timeline's, for clients to resume from (Last-Event-ID).
 */
// ponytail: in memory, one backend instance (as the spec allows); LISTEN/NOTIFY or a broker for more
@RestController
@RequestMapping("/api")
class WorkOrderEvents {

    private static final String READERS = "hasAnyRole('ADMIN','DEVELOPER','VIEWER')";

    public record Event(long id, UUID workOrderId, String type, Instant at, JsonNode data) {}

    /**
     * {@code workOrderId} null: every real work order's events (eval work orders aside). {@code
     * backlog}: the ids sent before the subscriber was live, not to send again.
     */
    private record Subscriber(UUID workOrderId, SseEmitter emitter, Set<Long> backlog) {
        Subscriber(UUID workOrderId) {
            this(workOrderId, new SseEmitter(STREAM_TIMEOUT_MS), ConcurrentHashMap.newKeySet());
        }
    }

    private static final long STREAM_TIMEOUT_MS = 30 * 60_000L; // clients reconnect, with Last-Event-ID

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    /** Events of transactions before this one were all streamed. */
    private String horizon;

    WorkOrderEvents(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @PreAuthorize(READERS)
    @GetMapping("/work-orders/{id}/timeline")
    List<Event> timeline(@PathVariable UUID id) {
        return query("select * from work_order_events where work_order_id = :workOrder order by id",
                Map.of("workOrder", id));
    }

    /** The work order's events: its whole timeline first, or what came after Last-Event-ID. */
    @PreAuthorize(READERS)
    @GetMapping(value = "/work-orders/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter workOrder(@PathVariable UUID id, @RequestHeader(value = "Last-Event-ID", required = false) Long after) {
        return subscribe(new Subscriber(id),
                "select * from work_order_events where work_order_id = :workOrder and id > :after order by id",
                Map.of("workOrder", id, "after", after == null ? 0 : after));
    }

    /** Every work order's events from now on, or from Last-Event-ID: what lists and boards refresh on. */
    @PreAuthorize(READERS)
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter all(@RequestHeader(value = "Last-Event-ID", required = false) Long after) {
        Subscriber subscriber = new Subscriber(null);
        return after == null ? subscribe(subscriber, null, Map.of())
                : subscribe(subscriber, "select * from work_order_events where not eval and id > :after order by id",
                Map.of("after", after));
    }

    /** The backlog is sent and the subscriber registered between two polls: nothing in between is lost. */
    private synchronized SseEmitter subscribe(Subscriber subscriber, String backlog, Map<String, Object> params) {
        if (backlog != null) {
            for (Event event : query(backlog, params)) {
                if (!send(subscriber, event)) {
                    return subscriber.emitter();
                }
                subscriber.backlog().add(event.id());
            }
        }
        subscribers.add(subscriber);
        Runnable remove = () -> subscribers.remove(subscriber);
        subscriber.emitter().onCompletion(remove);
        subscriber.emitter().onTimeout(remove);
        subscriber.emitter().onError(e -> remove.run());
        return subscriber.emitter();
    }

    @Scheduled(fixedDelayString = "${factory.events.poll-interval:500ms}")
    synchronized void poll() {
        String now = jdbc.sql("select pg_snapshot_xmin(pg_current_snapshot())::text").query(String.class).single();
        if (horizon != null && !subscribers.isEmpty()) {
            for (Row row : rows("""
                            select * from work_order_events
                            where tx >= cast(:from as xid8) and tx < cast(:to as xid8) order by id""",
                    Map.of("from", horizon, "to", now))) {
                for (Subscriber subscriber : subscribers) {
                    boolean concerned = subscriber.workOrderId() == null ? !row.eval()
                            : subscriber.workOrderId().equals(row.event().workOrderId());
                    if (concerned && !subscriber.backlog().remove(row.event().id())) {
                        send(subscriber, row.event());
                    }
                }
            }
        }
        horizon = now;
        subscribers.forEach(subscriber -> subscriber.backlog().clear()); // the next polls are past it
    }

    @Scheduled(fixedDelay = 15_000)
    void keepAlive() {
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.emitter().send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(subscriber);
            }
        }
    }

    /** False when the client is gone. */
    private boolean send(Subscriber subscriber, Event event) {
        try {
            subscriber.emitter().send(SseEmitter.event().id(Long.toString(event.id())).name(event.type())
                    .data(event, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(subscriber);
            return false;
        }
    }

    private record Row(Event event, boolean eval) {}

    private List<Event> query(String sql, Map<String, Object> params) {
        return rows(sql, params).stream().map(Row::event).toList();
    }

    private List<Row> rows(String sql, Map<String, Object> params) {
        return jdbc.sql(sql).params(params)
                .query((rs, i) -> new Row(new Event(rs.getLong("id"), rs.getObject("work_order_id", UUID.class),
                        rs.getString("type"), rs.getTimestamp("at").toInstant(), json.readTree(rs.getString("data"))),
                        rs.getBoolean("eval")))
                .list();
    }
}
