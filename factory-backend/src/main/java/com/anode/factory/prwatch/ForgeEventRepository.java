package com.anode.factory.prwatch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ForgeEventRepository extends JpaRepository<ForgeEvent, String> {

    Optional<ForgeEvent> findFirstByWorkOrderIdAndTrustedTrueAndConsumedAtIsNullOrderByReceivedAt(UUID workOrderId);

    List<ForgeEvent> findByWorkOrderIdOrderByReceivedAt(UUID workOrderId);
}
