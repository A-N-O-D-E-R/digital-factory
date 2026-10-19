package com.anode.factory.prompt;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface AgentRoleRepository extends JpaRepository<AgentRole, String> {

    List<AgentRole> findAllByOrderByName();
}
