package com.anode.factory.workorder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    Optional<Approval> findByWorkOrderIdAndStepIdAndVisit(UUID workOrderId, String stepId, int visit);

    List<Approval> findByWorkOrderIdOrderByCreatedAt(UUID workOrderId);

    List<Approval> findByDecisionOrderByCreatedAt(Approval.Decision decision);
}
