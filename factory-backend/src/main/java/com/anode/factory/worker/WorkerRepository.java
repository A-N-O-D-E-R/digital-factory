package com.anode.factory.worker;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WorkerRepository extends JpaRepository<Worker, UUID> {

    boolean existsByIdAndActiveTrue(UUID id);

    boolean existsByName(String name);

    List<Worker> findAllByOrderByName();
}
