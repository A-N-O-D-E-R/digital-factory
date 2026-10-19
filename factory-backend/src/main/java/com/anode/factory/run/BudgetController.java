package com.anode.factory.run;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class BudgetController {

    /** A missing budget is no cap. */
    record BudgetsRequest(@DecimalMin("0") BigDecimal dailyUsd, @DecimalMin("0") BigDecimal workOrderUsd) {}

    private final Budgets budgets;

    BudgetController(Budgets budgets) {
        this.budgets = budgets;
    }

    @GetMapping("/budgets")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','VIEWER')")
    Budgets.State state() {
        return budgets.state();
    }

    @PutMapping("/budgets")
    @PreAuthorize("hasRole('ADMIN')")
    Budgets.State budgets(@Valid @RequestBody BudgetsRequest request, Authentication admin) {
        return budgets.budgets(request.dailyUsd(), request.workOrderUsd(), admin.getName());
    }

    /** No run is dispatched until resumed; running ones finish. */
    @PostMapping("/factory/pause")
    @PreAuthorize("hasRole('ADMIN')")
    Budgets.State pause(Authentication admin) {
        return budgets.pause(true, admin.getName());
    }

    @PostMapping("/factory/resume")
    @PreAuthorize("hasRole('ADMIN')")
    Budgets.State resume(Authentication admin) {
        return budgets.pause(false, admin.getName());
    }
}
