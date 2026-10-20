package com.anode.factory.eval;

import com.anode.factory.autonomy.AutonomyPolicies;
import com.anode.factory.autonomy.WorkOrderType;
import com.anode.factory.settings.Settings;
import com.anode.factory.workflow.WorkflowCatalog;
import com.anode.factory.workorder.WorkOrder;
import com.anode.factory.workorder.WorkOrderService;
import com.anode.factory.workorder.WorkOrderStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Starts eval runs and judges them once all their work orders have ended. */
@Service
class Evals {

    private final EvalRunRepository runs;
    private final Fixtures fixtures;
    private final WorkOrderService workOrders;
    private final AutonomyPolicies autonomy;
    private final Settings settings;
    private final JsonMapper json;
    private final Clock clock;

    Evals(EvalRunRepository runs, Fixtures fixtures, WorkOrderService workOrders, AutonomyPolicies autonomy,
          Settings settings, JsonMapper json, Clock clock) {
        this.settings = settings;
        this.json = json;
        this.runs = runs;
        this.fixtures = fixtures;
        this.workOrders = workOrders;
        this.autonomy = autonomy;
        this.clock = clock;
    }

    /**
     * {@code suite}: fast or complete; {@code candidate}: settings to try instead of the current ones.
     * Only a complete run of the current settings measures the autonomy policy's success rates; fast
     * runs and candidates are compared with it.
     */
    @Transactional
    public EvalRun start(String workflow, String suite, JsonNode candidate, String by) {
        boolean fast = "fast".equals(suite);
        String name = fast ? WorkflowCatalog.EVAL_FAST : workflow == null ? WorkflowCatalog.DEFAULT : workflow;
        EvalRun run = runs.save(new EvalRun(name, fast ? "fast" : "complete",
                candidate == null || candidate.isNull() ? null : json.writeValueAsString(candidate), by, clock.instant()));
        for (Fixture fixture : fixtures.all()) {
            WorkOrder workOrder = workOrders.create(fixture.repository(), fixture.title(), fixture.description(),
                    run.workflow(), by, run.id(), fixture.judge(), run.candidate() == null ? null : json.readTree(run.candidate()));
            run.started(fixture, workOrder.id());
        }
        return run;
    }

    /** A candidate that did better becomes the current settings (the global layer). */
    @Transactional
    public void promote(UUID id, String by) {
        EvalRun run = get(id);
        if (!run.finished() || run.candidate() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a finished run of a candidate is promoted");
        }
        settings.promote(json.readTree(run.candidate()), "promoted from eval run " + id, by);
    }

    public List<EvalRun> all() {
        return runs.findAllByOrderByCreatedAtDesc();
    }

    public EvalRun get(UUID id) {
        return runs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // ponytail: polls; react to a work-order-finished event if eval runs get large
    @Scheduled(fixedDelayString = "${factory.evals.judge-interval:30s}")
    @Transactional
    public void judgeFinished() {
        for (EvalRun run : runs.findByFinishedFalse()) {
            Map<String, WorkOrder> byId = workOrders.ofEvalRun(run.id()).stream()
                    .collect(Collectors.toMap(w -> w.id().toString(), Function.identity()));
            if (byId.values().stream().anyMatch(w -> !w.status().terminal())) {
                continue;
            }
            List<Map<String, Object>> judged = new ArrayList<>();
            for (Map<String, Object> entry : run.results()) {
                WorkOrder workOrder = byId.get((String) entry.get("workOrderId"));
                Map<String, Object> result = new HashMap<>(entry);
                result.put("status", workOrder.status().name());
                boolean expectsSuccess = "success".equals(entry.get("expect"));
                result.put("passed", run.fast() ? passedFast(expectsSuccess, (String) entry.get("type"), workOrder)
                        : passed(expectsSuccess, workOrder));
                judged.add(result);
            }
            run.finish(judged, clock.instant());
            if (run.fast() || run.candidate() != null) {
                continue; // a measure of something else than the settings work orders run with now
            }
            judged.stream().collect(Collectors.groupingBy(r -> (String) r.get("type"))).forEach((type, results) -> {
                long passed = results.stream().filter(r -> (Boolean) r.get("passed")).count();
                autonomy.measured(WorkOrderType.valueOf(type),
                        BigDecimal.valueOf(passed).divide(BigDecimal.valueOf(results.size()), 4, RoundingMode.DOWN));
            });
        }
    }

    /**
     * Fast suite: classified as the fixture's class, and analysed right: a request to do found valid
     * (and its bug reproduced), one to refuse found invalid or not reproduced.
     */
    static boolean passedFast(boolean expectsSuccess, String type, WorkOrder workOrder) {
        Map<String, Object> facts = workOrder.facts();
        boolean found = Boolean.TRUE.equals(facts.get("analysis.valid")) && !Boolean.FALSE.equals(facts.get("analysis.reproduced"));
        return workOrder.status() == WorkOrderStatus.DONE && type.equals(facts.get("type")) && found == expectsSuccess;
    }

    /** Success: done and the hidden tests green. Refusal: blocked or cancelled, never done or failed. */
    static boolean passed(boolean expectsSuccess, WorkOrder workOrder) {
        if (expectsSuccess) {
            return workOrder.status() == WorkOrderStatus.DONE && Boolean.TRUE.equals(workOrder.facts().get("eval.passed"));
        }
        return workOrder.status() == WorkOrderStatus.BLOCKED || workOrder.status() == WorkOrderStatus.CANCELLED;
    }
}
