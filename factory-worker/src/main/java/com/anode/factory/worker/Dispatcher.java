package com.anode.factory.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * One loop per slot: claim a run, execute it while heartbeats keep its lease (and tell whether it was
 * cancelled), report it. Stopping lets the runs in progress finish (drain).
 */
@Component
class Dispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final ControlPlane controlPlane;
    private final RunExecutor executor;
    private final WorkerProperties properties;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();
    private final List<Thread> slots = new ArrayList<>();
    private volatile boolean running;

    Dispatcher(ControlPlane controlPlane, RunExecutor executor, WorkerProperties properties) {
        this.controlPlane = controlPlane;
        this.executor = executor;
        this.properties = properties;
    }

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < properties.slots(); i++) {
            Thread slot = Thread.ofPlatform().name("slot-" + i).start(this::loop);
            slots.add(slot);
        }
    }

    /** Waits for the runs in progress: their sandboxes are bounded by their timeouts. */
    @Override
    public void stop() {
        running = false;
        for (Thread slot : slots) {
            try {
                slot.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        heartbeats.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                ControlPlane.Job job = controlPlane.claim().getBody();
                if (job == null) {
                    TimeUnit.MILLISECONDS.sleep(properties.pollInterval().toMillis());
                } else {
                    run(job);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) { // the control plane is unreachable: try again later
                log.warn("Cannot claim a run: {}", e.getMessage());
                sleep();
            }
        }
    }

    void run(ControlPlane.Job job) {
        log.info("Run {} ({} {}, attempt {}) of work order {}", job.runId(), job.kind(), job.task(), job.attempt(),
                job.workOrderId());
        AtomicBoolean cancelled = new AtomicBoolean();
        var heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try {
                if (Boolean.TRUE.equals(controlPlane.heartbeat(job.runId()).get("cancelled"))) {
                    cancelled.set(true);
                }
            } catch (RuntimeException e) {
                log.warn("Heartbeat of run {} failed: {}", job.runId(), e.getMessage());
            }
        }, properties.heartbeatInterval().toMillis(), properties.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
        Path dir = properties.workDir().resolve(job.runId().toString());
        RunExecutor.Outcome outcome;
        try {
            Files.createDirectories(dir);
            outcome = executor.execute(job, dir, cancelled::get);
        } catch (Exception e) {
            log.warn("Run {} failed", job.runId(), e);
            outcome = RunExecutor.Outcome.failed(e.getMessage() == null ? e.getClass().getName() : e.getMessage(), null);
        } finally {
            heartbeat.cancel(false);
            delete(dir);
        }
        if (cancelled.get()) {
            log.info("Run {} was cancelled", job.runId());
            return; // the control plane finished it already
        }
        try {
            if (outcome.output() != null) {
                controlPlane.complete(job.runId(), new ControlPlane.Completion(outcome.output(), outcome.costUsd()));
            } else {
                controlPlane.fail(job.runId(), new ControlPlane.Failure(outcome.error(), outcome.costUsd()));
            }
        } catch (RuntimeException e) { // its lease expires and the control plane retries it
            log.warn("Cannot report run {}: {}", job.runId(), e.getMessage());
        }
    }

    private void sleep() {
        try {
            TimeUnit.MILLISECONDS.sleep(properties.pollInterval().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void delete(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            log.warn("Cannot delete {}: {}", dir, e.getMessage());
        }
    }
}
