package com.anode.factory.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class IntakeController {

    /** {@code ticket}: a key ({@code github:acme/api#12}) or the ticket's web URL. */
    record ImportRequest(@NotBlank @Size(max = 2000) String ticket, String repository, String workflow,
                         Boolean reimport) {}

    record ImportedView(String ticket, String url, UUID workOrderId, UUID ruleId, String reported,
                        Instant importedAt) {
        static ImportedView of(ImportedTicket t) {
            return new ImportedView(t.ticket(), t.url(), t.workOrderId(), t.ruleId(),
                    t.reported() == null ? null : t.reported().name(), t.importedAt());
        }
    }

    record RuleRequest(String name, @NotBlank String source, @Size(max = 255) String project,
                       @Size(max = 2000) String filter, @NotBlank String repository, String workflow, Integer maxRead,
                       Integer maxCreated, Boolean enabled) {
        Intake.RuleSettings settings() {
            return new Intake.RuleSettings(source, project, filter, repository, workflow, maxRead, maxCreated, enabled);
        }
    }

    record RuleView(UUID id, String name, String source, String project, String filter, String repository,
                    String workflow, int maxRead, int maxCreated, boolean enabled, Instant lastPolledAt,
                    String lastError, String createdBy, Instant createdAt) {
        static RuleView of(IntakeRule r) {
            return new RuleView(r.id(), r.name(), r.source(), r.project(), r.filter(), r.repository(), r.workflow(),
                    r.maxRead(), r.maxCreated(), r.enabled(), r.lastPolledAt(), r.lastError(), r.createdBy(),
                    r.createdAt());
        }
    }

    private final Intake intake;

    IntakeController(Intake intake) {
        this.intake = intake;
    }

    @PostMapping("/tickets/import")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    @ResponseStatus(HttpStatus.CREATED)
    ImportedView importTicket(@Valid @RequestBody ImportRequest request, Authentication user) {
        return ImportedView.of(intake.importTicket(request.ticket(), request.repository(), request.workflow(),
                Boolean.TRUE.equals(request.reimport()), user.getName()));
    }

    @GetMapping("/tickets")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    List<ImportedView> imported() {
        return intake.imported().stream().map(ImportedView::of).toList();
    }

    @GetMapping("/intake-rules")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    List<RuleView> rules() {
        return intake.rules().stream().map(RuleView::of).toList();
    }

    @PostMapping("/intake-rules")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<RuleView> create(@Valid @RequestBody RuleRequest request, Authentication admin) {
        IntakeRule rule = intake.createRule(request.name(), request.settings(), admin.getName());
        return ResponseEntity.created(URI.create("/api/intake-rules/" + rule.id())).body(RuleView.of(rule));
    }

    @PutMapping("/intake-rules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    RuleView update(@PathVariable UUID id, @Valid @RequestBody RuleRequest request) {
        return RuleView.of(intake.updateRule(id, request.settings()));
    }

    /** A pass now rather than at the next interval. */
    @PostMapping("/intake-rules/{id}/poll")
    @PreAuthorize("hasRole('ADMIN')")
    Intake.Pass poll(@PathVariable UUID id) {
        return intake.poll(id);
    }
}
