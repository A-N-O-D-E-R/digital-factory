package com.anode.factory.eval;

import com.anode.factory.autonomy.AutonomyPolicies;
import com.anode.factory.autonomy.WorkOrderType;
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

/** Starts eval runs and judges them once all their work orders have ended. */
@Service
class Evals {

    private final EvalRunRepository runs;
    private final Fixtures fixtures;
    private final WorkOrderService workOrders;
    private final AutonomyPolicies autonomy;
    private final Clock clock;

    Evals(EvalRunRepository runs, Fixtures fixtures, WorkOrderService workOrders, AutonomyPolicies autonomy, Clock clock) {
        this.runs = runs;
        this.fixtures = fixtures;
        this.workOrders = workOrders;
        this.autonomy = autonomy;
        this.clock = clock;
    }

    @Transactional
    public EvalRun start(String workflow, String by) {
        EvalRun run = runs.save(new EvalRun(workflow == null ? "default" : workflow, by, clock.instant()));
        for (Fixture fixture : fixtures.all()) {
            WorkOrder workOrder = workOrders.create(fixture.repository(), fixture.title(), fixture.description(),
                    run.workflow(), by, run.id(), fixture.judge());
            run.started(fixture, workOrder.id());
        }
        return run;
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
                result.put("passed", passed("success".equals(entry.get("expect")), workOrder));
                judged.add(result);
            }
            run.finish(judged, clock.instant());
            judged.stream().collect(Collectors.groupingBy(r -> (String) r.get("type"))).forEach((type, results) -> {
                long passed = results.stream().filter(r -> (Boolean) r.get("passed")).count();
                autonomy.measured(WorkOrderType.valueOf(type),
                        BigDecimal.valueOf(passed).divide(BigDecimal.valueOf(results.size()), 4, RoundingMode.DOWN));
            });
        }
    }

    /** Success: done and the hidden tests green. Refusal: blocked or cancelled, never done or failed. */
    static boolean passed(boolean expectsSuccess, WorkOrder workOrder) {
        if (expectsSuccess) {
            return workOrder.status() == WorkOrderStatus.DONE && Boolean.TRUE.equals(workOrder.facts().get("eval.passed"));
        }
        return workOrder.status() == WorkOrderStatus.BLOCKED || workOrder.status() == WorkOrderStatus.CANCELLED;
    }
}
