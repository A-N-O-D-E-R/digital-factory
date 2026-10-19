package com.anode.factory.prompt;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface PromptVersionRepository extends JpaRepository<PromptVersion, PromptVersion.Key> {

    Optional<PromptVersion> findFirstByRoleOrderByVersionDesc(String role);

    Optional<PromptVersion> findByRoleAndVersion(String role, int version);

    List<PromptVersion> findByRoleOrderByVersionDesc(String role);
}
