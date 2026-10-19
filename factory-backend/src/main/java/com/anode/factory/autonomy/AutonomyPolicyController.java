package com.anode.factory.autonomy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/autonomy-policy")
class AutonomyPolicyController {

    record PolicyView(WorkOrderType type, AutonomyLevel level, BigDecimal minSuccessRate, BigDecimal successRate,
                      BigDecimal maxFailureRate, BigDecimal failureRate, String updatedBy, Instant updatedAt) {
        static PolicyView of(AutonomyPolicy p) {
            return new PolicyView(p.type(), p.level(), p.minSuccessRate(), p.successRate(), p.maxFailureRate(),
                    p.failureRate(), p.updatedBy(), p.updatedAt());
        }

        static PolicyView none(WorkOrderType type) {
            return new PolicyView(type, AutonomyLevel.DRAFT_PR, null, null, null, null, null, null);
        }
    }

    /** {@code maxFailureRate}: 0.25 when not given. */
    record GrantRequest(@NotNull AutonomyLevel level,
                        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal minSuccessRate,
                        @DecimalMin("0") @DecimalMax("1") BigDecimal maxFailureRate) {}

    private final AutonomyPolicies policies;

    AutonomyPolicyController(AutonomyPolicies policies) {
        this.policies = policies;
    }

    /** One entry per class; classes without a policy stop at the draft PR. */
    @GetMapping
    List<PolicyView> all() {
        Map<WorkOrderType, AutonomyPolicy> byType = policies.all().stream()
                .collect(Collectors.toMap(AutonomyPolicy::type, Function.identity()));
        return Arrays.stream(WorkOrderType.values())
                .map(type -> byType.containsKey(type) ? PolicyView.of(byType.get(type)) : PolicyView.none(type))
                .toList();
    }

    @PutMapping("/{type}")
    @PreAuthorize("hasRole('ADMIN')")
    PolicyView grant(@PathVariable WorkOrderType type, @Valid @RequestBody GrantRequest request, Authentication admin) {
        return PolicyView.of(policies.grant(type, request.level(), request.minSuccessRate(),
                request.maxFailureRate(), admin.getName()));
    }
}
