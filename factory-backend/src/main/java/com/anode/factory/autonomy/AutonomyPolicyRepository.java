package com.anode.factory.autonomy;

import org.springframework.data.jpa.repository.JpaRepository;

interface AutonomyPolicyRepository extends JpaRepository<AutonomyPolicy, WorkOrderType> {}
