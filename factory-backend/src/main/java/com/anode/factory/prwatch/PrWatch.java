package com.anode.factory.prwatch;

import com.anode.factory.forge.Forges;
import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.Repositories;
import com.anode.factory.workorder.WorkOrder;
import com.anode.factory.workorder.WorkOrderService;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receives the forge events of the factory's PRs. Only events from trusted authors (write access
 * on the repository, or listed) and the forge itself steer a work order; bots and strangers are
 * recorded and ignored.
 */
@Service
class PrWatch {

    private static final Logger log = LoggerFactory.getLogger(PrWatch.class);

    private final ForgeEventRepository events;
    private final Repositories repositories;
    private final WorkOrderService workOrders;
    private final Forges forges;
    private final Set<String> trustedAuthors;
    private final Clock clock;

    PrWatch(ForgeEventRepository events, Repositories repositories, WorkOrderService workOrders, Forges forges,
            @Value("${factory.prwatch.trusted-authors:}") Set<String> trustedAuthors, Clock clock) {
        this.events = events;
        this.repositories = repositories;
        this.workOrders = workOrders;
        this.forges = forges;
        this.trustedAuthors = trustedAuthors;
        this.clock = clock;
    }

    // ponytail: an event racing the commit of its PR's number is dropped; add the polling fallback if seen
    @Transactional
    public void receive(PrEvent event) {
        if (events.existsById(event.id())) {
            return; // redelivered
        }
        Optional<CodeRepository> repository = repositories.find(event.repository());
        Optional<WorkOrder> workOrder = repository.flatMap(r -> workOrders.findByPullRequest(r.key(), event.number()));
        if (workOrder.isEmpty()) {
            log.debug("Ignoring {} on {}#{}: not a factory PR", event.kind(), event.repository(), event.number());
            return;
        }
        String ignoredBecause = ignoredBecause(event, repository.get());
        events.save(new ForgeEvent(event, workOrder.get().id(), ignoredBecause, clock.instant()));
        if (ignoredBecause == null) {
            workOrders.wake(workOrder.get().id());
        } else {
            log.info("Ignoring {} by {} on {}#{}: {}", event.kind(), event.author(), event.repository(),
                    event.number(), ignoredBecause);
        }
    }

    private String ignoredBecause(PrEvent event, CodeRepository repository) {
        if (event.system()) {
            return null;
        }
        if (event.bot()) {
            return "author is a bot";
        }
        if (trustedAuthors.contains(event.author()) || forges.of(repository).canWrite(repository, event.author())) {
            return null;
        }
        return "author cannot write to the repository";
    }

    /** The oldest trusted event the work order has not reacted to yet, marked as consumed. */
    @Transactional
    public Optional<ForgeEvent> next(UUID workOrderId) {
        Optional<ForgeEvent> next = events.findFirstByWorkOrderIdAndTrustedTrueAndConsumedAtIsNullOrderByReceivedAt(workOrderId);
        next.ifPresent(event -> event.consume(clock.instant()));
        return next;
    }

    public List<ForgeEvent> of(UUID workOrderId) {
        return events.findByWorkOrderIdOrderByReceivedAt(workOrderId);
    }
}
