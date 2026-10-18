package com.anode.factory.eval;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eval-runs")
class EvalRunController {

    record StartRequest(String workflow) {}

    record EvalRunView(UUID id, String workflow, boolean finished, List<Map<String, Object>> results, String createdBy,
                       Instant createdAt, Instant finishedAt) {
        static EvalRunView of(EvalRun r) {
            return new EvalRunView(r.id(), r.workflow(), r.finished(), r.results(), r.createdBy(), r.createdAt(),
                    r.finishedAt());
        }
    }

    private final Evals evals;

    EvalRunController(Evals evals) {
        this.evals = evals;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    EvalRunView start(@RequestBody(required = false) StartRequest request, Authentication admin) {
        return EvalRunView.of(evals.start(request == null ? null : request.workflow(), admin.getName()));
    }

    @GetMapping
    List<EvalRunView> all() {
        return evals.all().stream().map(EvalRunView::of).toList();
    }

    @GetMapping("/{id}")
    EvalRunView get(@PathVariable UUID id) {
        return EvalRunView.of(evals.get(id));
    }
}
