package com.anode.factory.eval;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface EvalRunRepository extends JpaRepository<EvalRun, UUID> {

    List<EvalRun> findByFinishedFalse();

    List<EvalRun> findAllByOrderByCreatedAtDesc();
}
