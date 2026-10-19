package com.anode.factory.run;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The queue of runs between the control plane and the workers. Workers claim runs over HTTP (they
 * never touch the database), report heartbeats, then complete or fail them. Outputs are checked
 * against their schema; failed or invalid runs are retried as new attempts, and {@link
 * RunFinished} is published once a run is final.
 */
@Service
public class RunQueue {

    private static final Logger log = LoggerFactory.getLogger(RunQueue.class);

    /** What a worker receives when it claims a run; an agent run must not spend more than {@code budgetUsd}. */
    public record Job(UUID runId, UUID workOrderId, RunKind kind, String task, int attempt,
                      @JsonRawValue String input, @JsonRawValue String outputSchema, Instant leaseUntil,
                      BigDecimal budgetUsd) {}

    private final RunRepository runs;
    private final OutputSchemas schemas;
    private final ApplicationEventPublisher events;
    private final RunProperties properties;
    private final Budgets budgets;
    private final Clock clock;
    private final JsonMapper json;

    RunQueue(RunRepository runs, OutputSchemas schemas, ApplicationEventPublisher events, RunProperties properties,
             Budgets budgets, Clock clock, JsonMapper json) {
        this.budgets = budgets;
        this.json = json;
        this.runs = runs;
        this.schemas = schemas;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Run enqueue(UUID workOrderId, String stepId, int visit, RunKind kind, String task, String input) {
        if (!schemas.exists(task)) {
            throw new IllegalArgumentException("No output schema for task '" + task + "'");
        }
        return runs.save(new Run(workOrderId, stepId, visit, kind, task, 1, input, clock.instant()));
    }

    /** The oldest queued run its budgets allow, with its budget reserved; none while the factory is paused. */
    @Transactional
    public Optional<Job> claim(UUID workerId) {
        runs.lockClaims();
        if (budgets.paused()) {
            return Optional.empty();
        }
        for (Run run : runs.lockQueued()) {
            BigDecimal reservation = reservation(run);
            if (budgets.refusal(run.workOrderId(), reservation).isEmpty()) {
                Instant now = clock.instant();
                run.start(workerId, now.plus(properties.lease()), reservation, now);
                return Optional.of(new Job(run.id(), run.workOrderId(), run.kind(), run.task(), run.attempt(),
                        run.input(), schemas.text(run.task()), now.plus(properties.lease()),
                        run.kind() == RunKind.AGENT ? reservation : null));
            }
        }
        return Optional.empty();
    }

    /** Why the next queued run of a work order is held back (budget, pause), if it is. */
    public Optional<String> pauseReason(UUID workOrderId) {
        return runs.findFirstByWorkOrderIdAndStatusOrderByCreatedAt(workOrderId, RunStatus.QUEUED)
                .flatMap(run -> budgets.refusal(workOrderId, reservation(run)));
    }

    /** Only agents call a model. */
    private BigDecimal reservation(Run run) {
        return run.kind() == RunKind.AGENT ? properties.agentRunUsd() : BigDecimal.ZERO;
    }

    /** Extends the lease; tells the worker whether the run was cancelled meanwhile. */
    @Transactional
    public boolean heartbeat(UUID runId, UUID workerId) {
        Run run = ownedRun(runId, workerId);
        if (run.status() == RunStatus.RUNNING) {
            run.extendLease(clock.instant().plus(properties.lease()));
            return false;
        }
        return true;
    }

    @Transactional
    public void complete(UUID runId, UUID workerId, String output, BigDecimal costUsd) {
        Run run = ownedRunning(runId, workerId);
        List<String> violations = schemas.violations(run.task(), output);
        if (violations.isEmpty()) {
            run.finish(RunStatus.SUCCEEDED, output, null, costUsd, clock.instant());
            finished(run);
        } else {
            run.finish(RunStatus.INVALID_OUTPUT, output, String.join("; ", violations), costUsd, clock.instant());
            retryOrFinish(run);
        }
    }

    @Transactional
    public void fail(UUID runId, UUID workerId, String error, BigDecimal costUsd) {
        Run run = ownedRunning(runId, workerId);
        run.finish(RunStatus.FAILED, null, error, costUsd, clock.instant());
        retryOrFinish(run);
    }

    /** Runs whose worker stopped sending heartbeats are failed, hence retried. */
    @Scheduled(fixedDelayString = "${factory.runs.reaper-interval:30s}")
    @Transactional
    public void reapExpiredLeases() {
        for (Run run : runs.lockExpiredLeases(clock.instant())) {
            log.warn("Run {} lost its worker {} (lease expired)", run.id(), run.workerId());
            run.finish(RunStatus.FAILED, null, "lease expired: the worker stopped sending heartbeats", null, clock.instant());
            retryOrFinish(run);
        }
    }

    @Transactional
    public void cancelAll(UUID workOrderId) {
        for (Run run : runs.findByWorkOrderIdOrderByCreatedAt(workOrderId)) {
            if (!run.status().finished()) {
                run.finish(RunStatus.CANCELLED, null, "work order cancelled", null, clock.instant());
            }
        }
    }

    /** A run {@code workerId} is executing now; anything else is not found (or not running) for it. */
    public Run running(UUID runId, UUID workerId) {
        return ownedRunning(runId, workerId);
    }

    /** The latest attempt of each run of a step visit. */
    public List<Run> runsOf(UUID workOrderId, String stepId, int visit) {
        return runs.findByWorkOrderIdAndStepIdAndVisitOrderByCreatedAt(workOrderId, stepId, visit);
    }

    public List<Run> runsOf(UUID workOrderId) {
        return runs.findByWorkOrderIdOrderByCreatedAt(workOrderId);
    }

    private void retryOrFinish(Run run) {
        if (run.attempt() < properties.maxAttempts()) {
            // the next attempt is told what went wrong (e.g. the schema violations)
            ObjectNode input = (ObjectNode) json.readTree(run.input());
            input.put("previousAttemptError", run.error());
            runs.save(new Run(run.workOrderId(), run.stepId(), run.visit(), run.kind(), run.task(), run.attempt() + 1,
                    json.writeValueAsString(input), clock.instant()));
        } else {
            finished(run);
        }
    }

    private void finished(Run run) {
        events.publishEvent(new RunFinished(run.id(), run.workOrderId(), run.stepId(), run.visit(), run.status()));
    }

    private Run ownedRun(UUID runId, UUID workerId) {
        Run run = runs.findById(runId).orElseThrow(() -> new RunNotFoundException(runId));
        if (!workerId.equals(run.workerId())) {
            throw new RunNotFoundException(runId); // not this worker's: do not reveal it exists
        }
        return run;
    }

    private Run ownedRunning(UUID runId, UUID workerId) {
        Run run = ownedRun(runId, workerId);
        if (run.status() != RunStatus.RUNNING) {
            throw new RunNotRunningException(runId, run.status());
        }
        return run;
    }
}
