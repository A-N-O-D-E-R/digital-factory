package com.anode.factory.prwatch;

import com.anode.factory.autonomy.AutonomyPolicies;
import com.anode.factory.forge.Forges;
import com.anode.factory.forge.PullRequest;
import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.Repositories;
import com.anode.factory.workorder.WorkOrder;
import com.anode.factory.workorder.WorkOrderService;
import com.anode.factory.workorder.WorkOrderStatus;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Pattern COMMAND = Pattern.compile("/factory\\s+(\\w+)");
    private static final Pattern REVERT = Pattern.compile("This reverts commit ([0-9a-f]{40})");

    private final ForgeEventRepository events;
    private final Repositories repositories;
    private final WorkOrderService workOrders;
    private final Forges forges;
    private final AutonomyPolicies autonomy;
    private final Set<String> trustedAuthors;
    private final Clock clock;

    PrWatch(ForgeEventRepository events, Repositories repositories, WorkOrderService workOrders, Forges forges,
            AutonomyPolicies autonomy, @Value("${factory.prwatch.trusted-authors:}") Set<String> trustedAuthors, Clock clock) {
        this.events = events;
        this.repositories = repositories;
        this.workOrders = workOrders;
        this.forges = forges;
        this.autonomy = autonomy;
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
        ForgeEvent saved = events.save(new ForgeEvent(event, workOrder.get().id(), ignoredBecause, clock.instant()));
        Matcher command = event.body() == null ? null : COMMAND.matcher(event.body().strip());
        if (ignoredBecause == null && !event.system() && command != null && command.lookingAt()) {
            saved.consume(clock.instant()); // a command for the factory, not feedback on the work
            command(command.group(1), event, workOrder.get(), repository.get());
        } else if (ignoredBecause == null) {
            workOrders.wake(workOrder.get().id());
        } else {
            log.info("Ignoring {} by {} on {}#{}: {}", event.kind(), event.author(), event.repository(),
                    event.number(), ignoredBecause);
        }
    }

    /**
     * {@code /factory stop} cancels the work order, {@code retry} reworks it from where it was blocked,
     * {@code explain} tells where it is and why; the answer is a comment on the PR.
     */
    private void command(String command, PrEvent event, WorkOrder workOrder, CodeRepository repository) {
        String by = event.author() + " on the PR";
        String reply = switch (command) {
            case "stop" -> {
                if (workOrder.status().terminal()) {
                    yield "Nothing to stop: work order " + workOrder.id() + " is " + workOrder.status() + ".";
                }
                workOrders.cancel(workOrder.id(), by);
                yield "Stopped: work order " + workOrder.id() + " is cancelled.";
            }
            case "retry" -> {
                if (workOrder.status() != WorkOrderStatus.BLOCKED) {
                    yield "Nothing to retry: work order " + workOrder.id() + " is " + workOrder.status() + ".";
                }
                workOrders.rework(workOrder.id(), "Retry asked by " + by, null, event.author());
                yield "Retrying work order " + workOrder.id() + " from where it was blocked.";
            }
            case "explain" -> "Work order " + workOrder.id() + " is " + workOrder.status()
                    + (workOrder.currentStep() == null ? "" : " at step " + workOrder.currentStep())
                    + (workOrder.outcomeReason() == null ? "" : ": " + workOrder.outcomeReason())
                    + (workOrder.facts().get("factory.reason") instanceof String last ? " (last transition: " + last + ")" : "")
                    + ".";
            default -> "Unknown command /factory " + command + ". Commands: stop, retry, explain.";
        };
        forges.of(repository).comment(repository, new PullRequest(event.number(), null, null, false), reply);
    }

    /**
     * A push to {@code branch}: when it is the default branch, the repository's open PRs are behind it,
     * and merges its commits revert ("This reverts commit …") count against their class.
     */
    @Transactional
    public void pushed(String deliveryId, String repositoryPath, String branch, List<String> messages) {
        Optional<CodeRepository> repository = repositories.find(repositoryPath);
        if (repository.isEmpty() || !repository.get().defaultBranch().equals(branch)) {
            return;
        }
        for (String message : messages) {
            Matcher reverted = REVERT.matcher(message);
            while (reverted.find()) {
                autonomy.reverted(reverted.group(1));
            }
        }
        for (WorkOrder workOrder : workOrders.withOpenPullRequest(repository.get().key())) {
            long number = ((Number) workOrder.facts().get("pr.number")).longValue();
            receive(new PrEvent(deliveryId + "#" + number, repositoryPath, number, "branch_behind", null, false, null,
                    null));
        }
    }

    private String ignoredBecause(PrEvent event, CodeRepository repository) {
        if (event.system()) {
            return null;
        }
        if (event.bot()) {
            return "author is a bot";
        }
        if (event.author().equals(forges.of(repository).self())) {
            return "author is the factory";
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
