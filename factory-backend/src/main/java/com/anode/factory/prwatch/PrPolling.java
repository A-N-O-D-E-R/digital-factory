package com.anode.factory.prwatch;

import com.anode.factory.forge.Forges;
import com.anode.factory.forge.PrActivity;
import com.anode.factory.forge.PullRequest;
import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.Repositories;
import com.anode.factory.workorder.WorkOrder;
import com.anode.factory.workorder.WorkOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The fallback of the webhooks: every open factory PR is read from its forge now and then, and what
 * a webhook missed is received like a delivery (an event seen both ways has one id, and counts once).
 */
// ponytail: reads every PR's whole activity each pass; ask the forge for what changed "since" if PRs pile up
@Component
class PrPolling {

    private static final Logger log = LoggerFactory.getLogger(PrPolling.class);

    private final WorkOrderService workOrders;
    private final Repositories repositories;
    private final Forges forges;
    private final PrWatch watch;

    PrPolling(WorkOrderService workOrders, Repositories repositories, Forges forges, PrWatch watch) {
        this.workOrders = workOrders;
        this.repositories = repositories;
        this.forges = forges;
        this.watch = watch;
    }

    @Scheduled(fixedDelayString = "${factory.prwatch.poll-interval:5m}")
    void poll() {
        for (WorkOrder workOrder : workOrders.withOpenPullRequest()) {
            try {
                CodeRepository repository = repositories.get(workOrder.repository());
                long number = ((Number) workOrder.facts().get("pr.number")).longValue();
                for (PrActivity activity : forges.of(repository).activity(repository,
                        new PullRequest(number, null, null, false))) {
                    watch.receive(new PrEvent(activity.id(), repository.key(), number, activity.kind(),
                            activity.author(), activity.bot(), activity.body(), activity.url(), activity.thread(),
                            activity.commit()));
                }
            } catch (RuntimeException e) {
                log.warn("Cannot poll the PR of work order {}", workOrder.id(), e);
            }
        }
    }
}
