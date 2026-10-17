package com.anode.factory.workflow;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, WorkflowVersion.Key> {

    Optional<WorkflowVersion> findFirstByNameOrderByVersionDesc(String name);

    Optional<WorkflowVersion> findByNameAndVersion(String name, int version);

    List<WorkflowVersion> findByNameOrderByVersionDesc(String name);

    /** The latest version of every workflow. */
    @Query("""
            select w from WorkflowVersion w
            where w.version = (select max(v.version) from WorkflowVersion v where v.name = w.name)
            order by w.name""")
    List<WorkflowVersion> findLatestVersions();
}
