package com.anode.factory.ticket;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface IntakeRuleRepository extends JpaRepository<IntakeRule, UUID> {

    List<IntakeRule> findAllByOrderByName();

    List<IntakeRule> findByEnabledTrue();

    boolean existsByName(String name);

    /** One pass of a rule at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from IntakeRule r where r.id = :id")
    Optional<IntakeRule> lock(UUID id);
}
