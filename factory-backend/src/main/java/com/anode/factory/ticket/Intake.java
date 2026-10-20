package com.anode.factory.ticket;

import com.anode.factory.repository.Repositories;
import com.anode.factory.workflow.WorkflowCatalog;
import com.anode.factory.workorder.WorkOrder;
import com.anode.factory.workorder.WorkOrderService;
import com.anode.factory.workorder.WorkOrderStatus;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tickets become work orders: by hand from a reference, or when an intake rule's pass finds them.
 * A ticket is imported once (re-importing it is explicit); its work order's progress is commented
 * back on it, each step once, and the ticket moved to the status configured for that step.
 */
@Service
public class Intake {

    private static final Logger log = LoggerFactory.getLogger(Intake.class);

    /** What one pass of a rule did. */
    public record Pass(int read, int created) {}

    public record RuleSettings(String source, String project, String filter, String repository, String workflow,
                               Integer maxRead, Integer maxCreated, Boolean enabled) {}

    private final TicketSources sources;
    private final IntakeRuleRepository rules;
    private final ImportedTicketRepository imported;
    private final WorkOrderService workOrders;
    private final Repositories repositories;
    private final WorkflowCatalog workflows;
    private final TransactionTemplate transaction;
    private final TicketProperties properties;
    private final Clock clock;

    Intake(TicketSources sources, IntakeRuleRepository rules, ImportedTicketRepository imported,
           WorkOrderService workOrders, Repositories repositories, WorkflowCatalog workflows,
           PlatformTransactionManager transactions, TicketProperties properties, Clock clock) {
        this.sources = sources;
        this.rules = rules;
        this.imported = imported;
        this.workOrders = workOrders;
        this.repositories = repositories;
        this.workflows = workflows;
        this.transaction = new TransactionTemplate(transactions);
        this.properties = properties;
        this.clock = clock;
    }

    /** {@code repository}: where the work goes; by default the ticket's own (issues). */
    @Transactional
    public ImportedTicket importTicket(String reference, String repository, String workflow, boolean reimport,
                                       String by) {
        TicketRef ref = sources.parse(reference);
        TicketSource source = sources.of(ref.source());
        Optional<ImportedTicket> existing = imported.lock(ref.key());
        if (existing.isPresent() && !reimport) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ref.key() + " is already imported as work order " + existing.get().workOrderId());
        }
        String target = repository != null ? repository : source.repositoryOf(ref).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Which repository is " + ref.key() + " for?"));
        return create(source.read(ref), target, workflow, null, by, existing);
    }

    public List<ImportedTicket> imported() {
        return imported.findAllByOrderByImportedAtDesc();
    }

    public List<IntakeRule> rules() {
        return rules.findAllByOrderByName();
    }

    @Transactional
    public IntakeRule createRule(String name, RuleSettings settings, String by) {
        if (name == null || !name.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid rule name");
        }
        if (rules.existsByName(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rule " + name + " exists");
        }
        IntakeRule rule = new IntakeRule(name, by, clock.instant());
        apply(rule, settings);
        return rules.save(rule);
    }

    @Transactional
    public IntakeRule updateRule(UUID id, RuleSettings settings) {
        IntakeRule rule = rules.lock(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        apply(rule, settings);
        return rule;
    }

    @Scheduled(fixedDelayString = "${factory.intake.interval:5m}")
    public void pollAll() {
        rules.findByEnabledTrue().forEach(rule -> poll(rule.id()));
    }

    /** One pass of a rule; its failure is recorded on it rather than thrown. */
    public Pass poll(UUID ruleId) {
        try {
            return transaction.execute(status -> pass(ruleId));
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw e;
            }
            recordError(ruleId, e);
        } catch (RuntimeException e) {
            recordError(ruleId, e);
        }
        return new Pass(0, 0);
    }

    private Pass pass(UUID ruleId) {
        IntakeRule rule = rules.lock(ruleId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        TicketSource source = sources.of(rule.source());
        List<Ticket> tickets = source.search(rule.project(), rule.filter(), rule.maxRead());
        int created = 0;
        for (Ticket ticket : tickets.subList(0, Math.min(tickets.size(), rule.maxRead()))) {
            if (created == rule.maxCreated()) {
                break;
            }
            if (!imported.existsById(ticket.ref().key())) {
                create(source.read(ticket.ref()), rule.repository(), rule.workflow(), rule.id(), "intake:" + rule.name(), Optional.empty());
                created++;
            }
        }
        rule.polled(clock.instant(), null);
        return new Pass(tickets.size(), created);
    }

    private void recordError(UUID ruleId, RuntimeException e) {
        log.warn("Intake rule {} failed", ruleId, e);
        String error = e instanceof ResponseStatusException r ? r.getReason() : e.getMessage();
        transaction.executeWithoutResult(status -> rules.findById(ruleId)
                .ifPresent(rule -> rule.polled(clock.instant(), error == null ? e.getClass().getName() : error)));
    }

    private ImportedTicket create(Ticket ticket, String repository, String workflow, UUID ruleId, String by,
                                  Optional<ImportedTicket> existing) {
        String title = ticket.title().length() > 300 ? ticket.title().substring(0, 300) : ticket.title();
        String body = ticket.body().isBlank() ? "(no description)" : ticket.body();
        if (body.length() > 99_000) {
            body = body.substring(0, 99_000);
        }
        // recorded before the workflow starts: its first steps may act on the ticket (comment-ticket...)
        ImportedTicket[] recorded = new ImportedTicket[1];
        workOrders.create(repository, title, body + "\n\nTicket: " + ticket.url(), workflow, by, workOrder -> {
            if (existing.isPresent()) {
                existing.get().reimported(workOrder.id(), clock.instant());
                recorded[0] = existing.get();
            } else {
                recorded[0] = imported.saveAndFlush(new ImportedTicket(ticket.ref().key(), ticket.url(), workOrder.id(),
                        ruleId, clock.instant()));
            }
        });
        return recorded[0];
    }

    private void apply(IntakeRule rule, RuleSettings s) {
        sources.of(s.source());
        if (s.project() == null || s.project().isBlank() || s.filter() == null || s.filter().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A project and a filter are required");
        }
        repositories.active(s.repository());
        if (s.workflow() != null && workflows.latest(s.workflow()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No workflow " + s.workflow());
        }
        int maxRead = s.maxRead() == null ? 50 : s.maxRead();
        int maxCreated = s.maxCreated() == null ? 5 : s.maxCreated();
        if (maxRead < 1 || maxRead > 100 || maxCreated < 1 || maxCreated > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxRead is 1 to 100, maxCreated 1 to 20");
        }
        rule.update(s.source(), s.project(), s.filter(), s.repository(), s.workflow(), maxRead, maxCreated,
                s.enabled() == null || s.enabled());
    }

    /** Comments each new step of the imported tickets' work orders; a failed comment is tried again next time. */
    @Scheduled(fixedDelayString = "${factory.intake.report-interval:1m}")
    public void report() {
        for (ImportedTicket ticket : imported.findByReportedIsNullOrReportedNot(ImportedTicket.Progress.FINISHED)) {
            try {
                transaction.executeWithoutResult(status -> report(ticket.ticket()));
            } catch (RuntimeException e) {
                log.warn("Cannot report progress on {}", ticket.ticket(), e);
            }
        }
    }

    private void report(String key) {
        ImportedTicket ticket = imported.lock(key).orElseThrow();
        WorkOrder workOrder = workOrders.get(ticket.workOrderId());
        ImportedTicket.Progress progress = workOrder.status().terminal() ? ImportedTicket.Progress.FINISHED
                : workOrder.facts().get("pr.url") != null ? ImportedTicket.Progress.PR_OPENED
                : ImportedTicket.Progress.STARTED;
        if (ticket.reported() != null && progress.compareTo(ticket.reported()) <= 0) {
            return;
        }
        String message = switch (progress) {
            case STARTED -> "The factory started work order " + workOrder.id() + " on this ticket.";
            case PR_OPENED -> "The factory opened a pull request: " + workOrder.facts().get("pr.url");
            case FINISHED -> "Work order " + workOrder.id() + " finished: " + workOrder.status()
                    + (workOrder.outcomeReason() == null ? "" : " — " + workOrder.outcomeReason());
        };
        TicketRef ref = sources.parse(key);
        TicketSource source = sources.of(ref.source());
        source.comment(ref, message);
        ticket.reported(progress);
        String step = switch (progress) {
            case STARTED -> "started";
            case PR_OPENED -> "pr-opened";
            case FINISHED -> workOrder.status() == WorkOrderStatus.DONE ? "done" : null;
        };
        String status = step == null ? null : properties.transition(ref.source(), step);
        if (status != null) {
            try {
                source.transition(ref, status);
            } catch (RuntimeException e) { // the comment is posted: moving the ticket is best effort
                log.warn("Cannot move {} to {}", key, status, e);
            }
        }
    }
}
