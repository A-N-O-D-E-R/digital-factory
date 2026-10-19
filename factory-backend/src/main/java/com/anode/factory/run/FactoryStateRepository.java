package com.anode.factory.run;

import org.springframework.data.jpa.repository.JpaRepository;

interface FactoryStateRepository extends JpaRepository<FactoryState, Integer> {}
