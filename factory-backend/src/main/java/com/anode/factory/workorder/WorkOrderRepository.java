package com.anode.factory.workorder;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface WorkOrderRepository extends JpaRepository<WorkOrder, UUID> {

    /** Serializes everything done to one work order (engine operations included). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkOrder w where w.id = :id")
    Optional<WorkOrder> lock(UUID id);

    List<WorkOrder> findByEvalRunIdIsNullOrderByCreatedAtDesc();

    List<WorkOrder> findByEvalRunIdOrderByCreatedAt(UUID evalRunId);

    @Query(value = "select * from work_orders where repository = :repository and facts ->> 'pr.number' = :number",
            nativeQuery = true)
    Optional<WorkOrder> findByPullRequest(String repository, String number);

    /** Running with an open PR, untouched since {@code before}: waiting on the PR, nothing happening. */
    @Query(value = """
            select * from work_orders where status = 'RUNNING' and jsonb_exists(facts, 'pr.number')
            and coalesce(facts ->> 'pr.event', '') not in ('merged', 'closed') and updated_at < :before""",
            nativeQuery = true)
    List<WorkOrder> findIdleWithOpenPullRequest(Instant before);

    /** The work orders watching an open PR. */
    @Query(value = """
            select * from work_orders where jsonb_exists(facts, 'pr.number')
            and status not in ('DONE', 'BLOCKED', 'FAILED', 'CANCELLED')
            and coalesce(facts ->> 'pr.event', '') not in ('merged', 'closed')""", nativeQuery = true)
    List<WorkOrder> findWithOpenPullRequest();

    /** The work orders watching an open PR on {@code repository}. */
    @Query(value = """
            select * from work_orders where repository = :repository and jsonb_exists(facts, 'pr.number')
            and status not in ('DONE', 'BLOCKED', 'FAILED', 'CANCELLED')
            and coalesce(facts ->> 'pr.event', '') not in ('merged', 'closed')""", nativeQuery = true)
    List<WorkOrder> findWithOpenPullRequest(String repository);

    /** Ended while their PR is still open (neither merged nor closed). */
    @Query(value = """
            select * from work_orders where status in ('DONE', 'BLOCKED', 'FAILED', 'CANCELLED') and jsonb_exists(facts, 'pr.number')
            and coalesce(facts ->> 'pr.event', '') not in ('merged', 'closed') and eval_run_id is null""",
            nativeQuery = true)
    List<WorkOrder> findEndedWithOpenPullRequest();
}
