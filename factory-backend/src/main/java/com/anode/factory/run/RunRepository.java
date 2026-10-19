package com.anode.factory.run;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RunRepository extends JpaRepository<Run, UUID> {

    /** Claims take this lock in turn, so that two reservations never fit the same budget room. */
    @Query(value = "select 1 from pg_advisory_xact_lock(4242)", nativeQuery = true)
    Integer lockClaims();

    /** The oldest queued runs, locked; runs locked by a concurrent cancellation are skipped. */
    // ponytail: first 20 only, runs held by their budget can starve later ones; filter in SQL if that bites
    @Query(value = "select * from runs where status = 'QUEUED' order by created_at limit 20 for update skip locked",
            nativeQuery = true)
    List<Run> lockQueued();

    @Query(value = "select coalesce(sum(coalesce(cost_usd, reserved_usd)), 0) from runs where work_order_id = :workOrderId",
            nativeQuery = true)
    BigDecimal spentBy(UUID workOrderId);

    @Query(value = "select coalesce(sum(coalesce(cost_usd, reserved_usd)), 0) from runs where started_at >= :since",
            nativeQuery = true)
    BigDecimal spentSince(Instant since);

    Optional<Run> findFirstByWorkOrderIdAndStatusOrderByCreatedAt(UUID workOrderId, RunStatus status);

    @Query(value = "select * from runs where status = 'RUNNING' and lease_until < :now for update skip locked",
            nativeQuery = true)
    List<Run> lockExpiredLeases(Instant now);

    List<Run> findByWorkOrderIdAndStepIdAndVisitOrderByCreatedAt(UUID workOrderId, String stepId, int visit);

    List<Run> findByWorkOrderIdOrderByCreatedAt(UUID workOrderId);
}
