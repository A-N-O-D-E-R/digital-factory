package com.anode.factory.prwatch;

import com.anode.factory.forge.Forges;
import com.anode.factory.forge.PullRequest;
import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.Repositories;
import com.anode.factory.workorder.WorkOrder;
import com.anode.factory.workorder.WorkOrderService;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The end of watching a PR: a PR left without activity stops its work order, and a work order that
 * ends while its PR is open (blocked, failed, cancelled, or bounds reached) says so on the PR, once.
 * Feedback iterations, CI fixes and budget are bounded by the workflow and the budgets.
 */
@Component
class PrLimits {

    private static final Logger log = LoggerFactory.getLogger(PrLimits.class);

    private final WorkOrderService workOrders;
    private final Repositories repositories;
    private final Forges forges;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Duration inactivity;
    private final Clock clock;

    PrLimits(WorkOrderService workOrders, Repositories repositories, Forges forges, JdbcClient jdbc,
             TransactionTemplate transaction, @Value("${factory.prwatch.inactivity:14d}") Duration inactivity,
             Clock clock) {
        this.workOrders = workOrders;
        this.repositories = repositories;
        this.forges = forges;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.inactivity = inactivity;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${factory.prwatch.limits-interval:10m}")
    void enforce() {
        for (WorkOrder idle : workOrders.idleWithOpenPullRequest(clock.instant().minus(inactivity))) {
            run(idle.id(), () -> workOrders.cancel(idle.id(), "PR inactivity (" + inactivity.toDays() + " days)"));
        }
        for (WorkOrder ended : workOrders.endedWithOpenPullRequest()) {
            run(ended.id(), () -> summarize(ended));
        }
    }

    /** Recorded first: a failed comment is not posted twice, it is logged. */
    private void summarize(WorkOrder workOrder) {
        int inserted = jdbc.sql("insert into pr_summaries (work_order_id, at) values (:id, :at) on conflict do nothing")
                .param("id", workOrder.id()).param("at", java.sql.Timestamp.from(clock.instant())).update();
        if (inserted == 0) {
            return;
        }
        CodeRepository repository = repositories.get(workOrder.repository());
        long number = ((Number) workOrder.facts().get("pr.number")).longValue();
        forges.of(repository).comment(repository, new PullRequest(number, null, null, false),
                "The factory stopped working on this PR: work order " + workOrder.id() + " is " + workOrder.status()
                        + (workOrder.outcomeReason() == null ? "." : " — " + workOrder.outcomeReason() + ".")
                        + " A trusted author can comment /factory retry if it is blocked.");
    }

    private void run(UUID workOrderId, Runnable action) {
        try {
            transaction.executeWithoutResult(status -> action.run());
        } catch (RuntimeException e) {
            log.warn("PR limits of work order {} failed", workOrderId, e);
        }
    }
}
