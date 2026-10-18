package com.anode.factory.forge;

import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.Repositories;
import com.anode.factory.run.Run;
import com.anode.factory.run.RunKind;
import com.anode.factory.run.RunQueue;
import com.anode.factory.workorder.WorkOrderService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Git credentials for the run a worker is executing, asked just in time: read access to clone,
 * write access only for a push run.
 */
@RestController
@PreAuthorize("hasRole('WORKER')")
class WorkerCredentialController {

    private final RunQueue runs;
    private final WorkOrderService workOrders;
    private final Repositories repositories;
    private final Forges forges;

    WorkerCredentialController(RunQueue runs, WorkOrderService workOrders, Repositories repositories, Forges forges) {
        this.runs = runs;
        this.workOrders = workOrders;
        this.repositories = repositories;
        this.forges = forges;
    }

    @PostMapping("/internal/runs/{runId}/credential")
    GitCredential credential(@PathVariable UUID runId, Authentication worker) {
        Run run = runs.running(runId, UUID.fromString(worker.getName()));
        CodeRepository repository = repositories.get(workOrders.get(run.workOrderId()).repository());
        return forges.of(repository).credential(repository, run.kind() == RunKind.PUSH ? Access.WRITE : Access.READ);
    }
}
