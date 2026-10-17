package com.anode.factory.run;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RunRepository extends JpaRepository<Run, UUID> {

    /** The oldest queued run, locked; runs locked by a concurrent claim are skipped. */
    @Query(value = "select * from runs where status = 'QUEUED' order by created_at limit 1 for update skip locked",
            nativeQuery = true)
    Optional<Run> lockNextQueued();

    @Query(value = "select * from runs where status = 'RUNNING' and lease_until < :now for update skip locked",
            nativeQuery = true)
    List<Run> lockExpiredLeases(Instant now);

    List<Run> findByWorkOrderIdAndStepIdAndVisitOrderByCreatedAt(UUID workOrderId, String stepId, int visit);

    List<Run> findByWorkOrderIdOrderByCreatedAt(UUID workOrderId);
}
