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
    public void prOutcome(UUID workOrderId, String type, boolean merged, String mergeCommit, int feedbackIterations) {
        WorkOrderType workOrderType;
        try {
            workOrderType = WorkOrderType.valueOf(type);
        } catch (IllegalArgumentException unknown) {
            return;
        }
        jdbc.sql("""
                        insert into pr_outcomes (work_order_id, work_order_type, merged, merge_commit, feedback_iterations, at)
                        values (:workOrder, :type, :merged, :commit, :iterations, :at) on conflict do nothing""")
                .param("workOrder", workOrderId).param("type", workOrderType.name()).param("merged", merged)
                .param("commit", mergeCommit).param("iterations", feedbackIterations)
                .param("at", java.sql.Timestamp.from(clock.instant())).update();
        measureFailures(workOrderType);
    }

    /** A factory PR's merge was reverted on its default branch: a failure of its class after all. */
    @Transactional
    public void reverted(String mergeCommit) {
        jdbc.sql("update pr_outcomes set reverted = true where merge_commit = :commit returning work_order_type")
                .param("commit", mergeCommit).query(String.class).list()
                .forEach(type -> measureFailures(WorkOrderType.valueOf(type)));
    }

    /** The class's latest PRs: closed without merge, or merged then reverted, are failures. */
    private void measureFailures(WorkOrderType type) {
        List<Boolean> failed = jdbc.sql("""
                        select not merged or reverted from pr_outcomes where work_order_type = :type
                        order by at desc limit :window""")
                .param("type", type.name()).param("window", WINDOW).query(Boolean.class).list();
        if (failed.size() < MIN_OUTCOMES) {
            return;
        }
        long failures = failed.stream().filter(f -> f).count();
        BigDecimal rate = BigDecimal.valueOf(failures).divide(BigDecimal.valueOf(failed.size()), 4, RoundingMode.HALF_UP);
        policies.findById(type).ifPresent(policy -> policy.failed(rate,
                failures + " of the last " + failed.size() + " PRs closed without merge or reverted", clock.instant()));
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
     * The {@code autonomy.allows} fact: the class has earned merging, the work is not HIGH risk, and
     * every piece of the factory's evidence is known and green — tests, acceptance tests, review,
     * secret scan. Anything unknown denies. The forge's own checks come later, from the PR: the merge action requires them too.
     */
    public boolean allowsMerge(Map<String, Object> facts) {
        boolean green = Boolean.TRUE.equals(facts.get("tests.passed"))
                && Boolean.TRUE.equals(facts.get("acceptance.passed"))
                && "PASS".equals(facts.get("verdict"))
                && Boolean.FALSE.equals(facts.get("secrets.found"));
        if (!green || "HIGH".equals(facts.get("risk")) || !(facts.get("type") instanceof String type)) {
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
