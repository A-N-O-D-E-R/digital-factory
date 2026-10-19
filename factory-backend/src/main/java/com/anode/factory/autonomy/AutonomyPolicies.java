package com.anode.factory.autonomy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Decides whether the factory may merge a work order's PR without a human. */
@Service
public class AutonomyPolicies {

    static final BigDecimal DEFAULT_MAX_FAILURE_RATE = new BigDecimal("0.25");
    /** The PRs the failure rate is measured on, and how many it takes to measure it. */
    // ponytail: closed without merge only; reverts after the merge are not watched yet
    private static final int WINDOW = 20;
    private static final int MIN_OUTCOMES = 5;

    private final AutonomyPolicyRepository policies;
    private final JdbcClient jdbc;
    private final Clock clock;

    AutonomyPolicies(AutonomyPolicyRepository policies, JdbcClient jdbc, Clock clock) {
        this.policies = policies;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * A PR of the factory merged or closed without merge (its work order ended): recorded per class,
     * and the class's real-world failure rate measured on its latest PRs.
     */
    @Transactional
    public void prOutcome(UUID workOrderId, String type, boolean merged) {
        WorkOrderType workOrderType;
        try {
            workOrderType = WorkOrderType.valueOf(type);
        } catch (IllegalArgumentException unknown) {
            return;
        }
        jdbc.sql("""
                        insert into pr_outcomes (work_order_id, work_order_type, merged, at)
                        values (:workOrder, :type, :merged, :at) on conflict do nothing""")
                .param("workOrder", workOrderId).param("type", workOrderType.name()).param("merged", merged)
                .param("at", java.sql.Timestamp.from(clock.instant())).update();
        List<Boolean> latest = jdbc.sql("""
                        select merged from pr_outcomes where work_order_type = :type order by at desc limit :window""")
                .param("type", workOrderType.name()).param("window", WINDOW).query(Boolean.class).list();
        if (latest.size() < MIN_OUTCOMES) {
            return;
        }
        long closed = latest.stream().filter(m -> !m).count();
        BigDecimal rate = BigDecimal.valueOf(closed).divide(BigDecimal.valueOf(latest.size()), 4, RoundingMode.HALF_UP);
        policies.findById(workOrderType).ifPresent(policy -> policy.failed(rate,
                closed + " of the last " + latest.size() + " PRs closed without merge", clock.instant()));
    }

    public List<AutonomyPolicy> all() {
        return policies.findAll();
    }

    @Transactional
    public AutonomyPolicy grant(WorkOrderType type, AutonomyLevel level, BigDecimal minSuccessRate,
                                BigDecimal maxFailureRate, String by) {
        AutonomyPolicy policy = policies.findById(type).orElseGet(() -> new AutonomyPolicy(type));
        policy.grant(level, minSuccessRate, maxFailureRate == null ? DEFAULT_MAX_FAILURE_RATE : maxFailureRate, by,
                clock.instant());
        return policies.save(policy);
    }

    /** What the latest eval run measured for the class; a class without a policy stays at the draft PR. */
    @Transactional
    public void measured(WorkOrderType type, BigDecimal successRate) {
        AutonomyPolicy policy = policies.findById(type).orElseGet(() -> {
            AutonomyPolicy created = new AutonomyPolicy(type);
            created.grant(AutonomyLevel.DRAFT_PR, BigDecimal.ONE, DEFAULT_MAX_FAILURE_RATE, "evals", clock.instant());
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
