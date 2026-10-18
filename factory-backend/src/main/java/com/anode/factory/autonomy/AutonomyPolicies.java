package com.anode.factory.autonomy;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Decides whether the factory may merge a work order's PR without a human. */
@Service
public class AutonomyPolicies {

    private final AutonomyPolicyRepository policies;
    private final Clock clock;

    AutonomyPolicies(AutonomyPolicyRepository policies, Clock clock) {
        this.policies = policies;
        this.clock = clock;
    }

    public List<AutonomyPolicy> all() {
        return policies.findAll();
    }

    @Transactional
    public AutonomyPolicy grant(WorkOrderType type, AutonomyLevel level, BigDecimal minSuccessRate, String by) {
        AutonomyPolicy policy = policies.findById(type).orElseGet(() -> new AutonomyPolicy(type));
        policy.grant(level, minSuccessRate, by, clock.instant());
        return policies.save(policy);
    }

    /** What the latest eval run measured for the class; a class without a policy stays at the draft PR. */
    @Transactional
    public void measured(WorkOrderType type, BigDecimal successRate) {
        AutonomyPolicy policy = policies.findById(type).orElseGet(() -> {
            AutonomyPolicy created = new AutonomyPolicy(type);
            created.grant(AutonomyLevel.DRAFT_PR, BigDecimal.ONE, "evals", clock.instant());
            return policies.save(created);
        });
        policy.measured(successRate);
    }

    /**
     * The {@code autonomy.allows} fact: the class has earned merging, and every piece of the factory's
     * evidence is known and green — tests, acceptance tests, review, secret scan. Anything unknown
     * denies. The forge's own checks come later, from the PR: the merge action requires them too.
     */
    public boolean allowsMerge(Map<String, Object> facts) {
        boolean green = Boolean.TRUE.equals(facts.get("tests.passed"))
                && Boolean.TRUE.equals(facts.get("acceptance.passed"))
                && "PASS".equals(facts.get("verdict"))
                && Boolean.FALSE.equals(facts.get("secrets.found"));
        if (!green || !(facts.get("type") instanceof String type)) {
            return false;
        }
        WorkOrderType workOrderType;
        try {
            workOrderType = WorkOrderType.valueOf(type);
        } catch (IllegalArgumentException unknown) {
            return false;
        }
        return policies.findById(workOrderType).map(AutonomyPolicy::mergeEarned).orElse(false);
    }
}
