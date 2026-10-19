package com.anode.factory.run;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The work of code-writing runs as git bundles (the work branch from the default branch), so that the
 * next run of the work order, on any worker, starts from it. Kept apart from {@link Run}: runs are
 * read often, bundles once per run.
 */
@Component
class RunBundles {

    private final JdbcClient jdbc;

    RunBundles(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A run uploading again replaces its bundle (a retried upload). */
    @Transactional
    void save(UUID runId, byte[] bundle) {
        jdbc.sql("delete from run_bundles where run_id = :run").param("run", runId).update();
        jdbc.sql("insert into run_bundles (run_id, bundle) values (:run, :bundle)")
                .param("run", runId).param("bundle", bundle).update();
    }

    /** The bundle of the work order's latest succeeded run that left one: what the work branch is now. */
    Optional<byte[]> latest(UUID workOrderId) {
        return jdbc.sql("""
                        select b.bundle from run_bundles b join runs r on r.id = b.run_id
                        where r.work_order_id = :workOrder and r.status = 'SUCCEEDED'
                        order by r.finished_at desc limit 1""")
                .param("workOrder", workOrderId)
                .query(byte[].class)
                .optional();
    }
}
