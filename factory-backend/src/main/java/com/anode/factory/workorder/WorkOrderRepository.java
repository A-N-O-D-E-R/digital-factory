package com.anode.factory.workorder;

import jakarta.persistence.LockModeType;
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

    List<WorkOrder> findAllByOrderByCreatedAtDesc();
}
