package com.anode.factory.worker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/**
 * Containers a run executes in: no capabilities, no privilege gain, a read-only root (a tmpfs for
 * /tmp, the home), the worker's uid, CPU, memory and pids limits, and the configured network only.
 */
@Component
class Sandbox {

    /** What a container did; {@code logs}: the tail of its output, for errors. */
    record Exit(int code, boolean timedOut, String logs) {}

    /** A directory of the host mounted in the container. */
    record Mount(Path host, String container, boolean writable) {}

    record Spec(String image, List<String> command, Map<String, String> env, List<Mount> mounts, double cpus,
                int memoryMb, Duration timeout) {}

    private final DockerClient docker;
    private final WorkerProperties properties;
    private final Map<String, Boolean> pulled = new ConcurrentHashMap<>();

    Sandbox(WorkerProperties properties) {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var http = new ZerodepDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).build();
        this.docker = DockerClientImpl.getInstance(config, http);
        this.properties = properties;
    }

    /** Runs to the end, the timeout, or until {@code cancelled} says so; the container is removed after. */
    Exit run(Spec spec, BooleanSupplier cancelled) {
        pull(spec.image());
        Map<String, String> env = new LinkedHashMap<>(spec.env());
        env.put("HOME", "/tmp/home");
        if (properties.egressProxy() != null) {
            env.putIfAbsent("HTTPS_PROXY", properties.egressProxy());
            env.putIfAbsent("HTTP_PROXY", properties.egressProxy());
            // Maven and the JVM ignore the variables above
            URI proxy = URI.create(properties.egressProxy());
            env.putIfAbsent("JAVA_TOOL_OPTIONS", "-Dhttps.proxyHost=%1$s -Dhttps.proxyPort=%2$d -Dhttp.proxyHost=%1$s -Dhttp.proxyPort=%2$d"
                    .formatted(proxy.getHost(), proxy.getPort() == -1 ? 3128 : proxy.getPort()));
        }
        List<Bind> binds = spec.mounts().stream()
                .map(m -> new Bind(m.host().toAbsolutePath().toString(), new Volume(m.container()),
                        m.writable() ? AccessMode.rw : AccessMode.ro))
                .toList();
        HostConfig host = HostConfig.newHostConfig()
                .withBinds(binds)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withReadonlyRootfs(true)
                .withTmpFs(Map.of("/tmp", "rw,exec,size=2g"))
                .withNetworkMode(properties.network())
                .withNanoCPUs((long) (spec.cpus() * 1_000_000_000L))
                .withMemory(spec.memoryMb() * 1024L * 1024L)
                .withPidsLimit(1024L);
        CreateContainerCmd create = docker.createContainerCmd(spec.image())
                .withUser(uid(spec.mounts().getFirst().host()))
                .withEnv(env.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList())
                .withWorkingDir("/workspace")
                .withHostConfig(host);
        if (!spec.command().isEmpty()) {
            create.withCmd(spec.command());
        }
        String id = create.exec().getId();
        try {
            docker.startContainerCmd(id).exec();
            long deadline = System.nanoTime() + spec.timeout().toNanos();
            while (true) {
                var state = docker.inspectContainerCmd(id).exec().getState();
                if (!Boolean.TRUE.equals(state.getRunning())) {
                    return new Exit(state.getExitCodeLong() == null ? -1 : state.getExitCodeLong().intValue(), false,
                            logs(id));
                }
                if (System.nanoTime() > deadline || cancelled.getAsBoolean()) {
                    docker.killContainerCmd(id).exec();
                    return new Exit(-1, System.nanoTime() > deadline, logs(id));
                }
                TimeUnit.MILLISECONDS.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        } finally {
            docker.removeContainerCmd(id).withForce(true).exec();
        }
    }

    private void pull(String image) {
        pulled.computeIfAbsent(image, i -> {
            try {
                docker.inspectImageCmd(i).exec();
            } catch (NotFoundException e) {
                try {
                    docker.pullImageCmd(i).start().awaitCompletion();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted pulling " + i, ie);
                }
            }
            return true;
        });
    }

    /** The owner of the workspace: the container writes it as the worker would. */
    private static String uid(Path workspace) {
        try {
            return Files.getAttribute(workspace, "unix:uid") + ":" + Files.getAttribute(workspace, "unix:gid");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String logs(String id) {
        List<String> lines = new ArrayList<>();
        try {
            docker.logContainerCmd(id).withStdOut(true).withStdErr(true).withTail(100)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            lines.add(new String(frame.getPayload(), StandardCharsets.UTF_8).stripTrailing());
                        }
                    })
                    .awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return String.join("\n", lines);
    }
}
