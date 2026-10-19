package com.anode.factory.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The integrations, connected or failing with their error: checked when asked, at most once a minute
 * each (refresh forces it). Credentials are never shown, only whether they work.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
class IntegrationController {

    /** {@code configured} false: no credential for it (the other fields are null). */
    record Status(String name, boolean configured, Boolean ok, String detail, String error, Instant checkedAt) {}

    private static final List<String> KNOWN = List.of("github", "gitlab", "jira", "notion");
    private static final Duration FRESH = Duration.ofMinutes(1);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Map<String, IntegrationCheck> checks;
    private final Map<String, Status> last = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Clock clock;

    IntegrationController(List<IntegrationCheck> checks, Clock clock) {
        this.checks = checks.stream().collect(Collectors.toMap(IntegrationCheck::name, Function.identity()));
        this.clock = clock;
    }

    @GetMapping("/api/integrations")
    List<Status> integrations(@RequestParam(defaultValue = "false") boolean refresh) {
        Map<String, Future<Status>> running = checks.values().stream()
                .filter(check -> refresh || stale(last.get(check.name())))
                .collect(Collectors.toMap(IntegrationCheck::name, check -> executor.submit(() -> check(check))));
        running.forEach((name, future) -> last.put(name, result(name, future)));
        return Stream.concat(KNOWN.stream(), checks.keySet().stream()).distinct()
                .map(name -> checks.containsKey(name) ? last.get(name)
                        : new Status(name, false, null, null, null, null))
                .toList();
    }

    private boolean stale(Status status) {
        return status == null || status.checkedAt().plus(FRESH).isBefore(clock.instant());
    }

    private Status check(IntegrationCheck check) {
        try {
            return new Status(check.name(), true, true, check.check(), null, clock.instant());
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Status(check.name(), true, false, null, error, clock.instant());
        }
    }

    private Status result(String name, Future<Status> future) {
        try {
            return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new Status(name, true, false, null, "No answer within " + TIMEOUT.toSeconds() + "s", clock.instant());
        } catch (Exception e) {
            return new Status(name, true, false, null, e.getMessage(), clock.instant());
        }
    }
}
