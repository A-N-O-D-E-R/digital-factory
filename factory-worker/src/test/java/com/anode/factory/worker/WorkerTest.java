package com.anode.factory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The worker against a stub control plane, a local bare repository as the forge, and real Docker
 * sandboxes (alpine standing in for the sandbox image, a shell script for the runner).
 */
class WorkerTest {

    static final String BRANCH = "factory/wo-1";
    static final String REPORTS = "**/target/surefire-reports/*.xml";
    /** The runner's part: change the tree, leave a result. */
    static final String AGENT = """
            echo fixed > fix.txt && echo 'aws = AKIAABCDEFGHIJKLMNOP' > config.txt && \
            printf '{"output":{"summary":"s","commitMessage":"fix: it"},"costUsd":0.42}' > /out/result.json""";

    @TempDir Path tmp;
    final JsonMapper json = JsonMapper.builder().build();
    HttpServer server;
    Path origin;
    /** What the stub control plane holds or received, by run: "bundle", "complete", "fail". */
    final Map<String, byte[]> received = new ConcurrentHashMap<>();
    volatile byte[] bundle;
    Dispatcher dispatcher;

    @BeforeEach
    void start() throws Exception {
        origin = tmp.resolve("origin.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(origin.toFile()).call().close();
        try (Git seed = Git.cloneRepository().setURI(origin.toUri().toString()).setDirectory(tmp.resolve("seed").toFile()).call()) {
            Files.writeString(tmp.resolve("seed/README.md"), "hello\n");
            seed.add().addFilepattern(".").call();
            seed.commit().setMessage("init").setAuthor("t", "t@t").call();
            seed.push().setRefSpecs(new org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/main")).call();
        }
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/runs/", exchange -> {
            String[] path = exchange.getRequestURI().getPath().split("/"); // "", internal, runs, id, action
            String action = exchange.getRequestMethod() + " " + path[4];
            byte[] body = exchange.getRequestBody().readAllBytes();
            byte[] response = new byte[0];
            int status = 200;
            if (!"Bearer t".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                status = 401;
            } else {
                switch (action) {
                    case "POST credential" -> response = json.writeValueAsBytes(Map.of("cloneUrl",
                            origin.toUri().toString(), "username", "x-access-token", "password", "secret",
                            "expiresAt", "2030-01-01T00:00:00Z"));
                    case "GET bundle" -> {
                        if (bundle == null) {
                            status = 204;
                        } else {
                            response = bundle;
                        }
                    }
                    case "PUT bundle" -> received.put("bundle", body);
                    case "POST heartbeat" -> response = "{\"cancelled\":false}".getBytes();
                    case "POST complete", "POST fail" -> received.put(path[4], body);
                    default -> status = 404;
                }
            }
            exchange.getResponseHeaders().add("Content-Type",
                    action.equals("GET bundle") ? "application/octet-stream" : "application/json");
            if (status == 204 || response.length == 0) {
                exchange.sendResponseHeaders(status == 200 ? 204 : status, -1);
            } else {
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        WorkerProperties properties = new WorkerProperties("http://localhost:" + server.getAddress().getPort(), "t",
                1, tmp.resolve("work"), Duration.ofMillis(100), Duration.ofSeconds(1), "none", null, null,
                List.of("sh", "-c", AGENT), Duration.ofMinutes(2), 1.0, 256);
        ControlPlane controlPlane = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(
                        RestClient.builder().baseUrl(properties.controlPlaneUrl())
                                .defaultHeader("Authorization", "Bearer t").build()))
                .build().createClient(ControlPlane.class);
        dispatcher = new Dispatcher(controlPlane, new RunExecutor(controlPlane, new Sandbox(properties), properties,
                json), properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ControlPlane.Job job(String kind, String task, String testCommand, Map<String, Object> artifacts) {
        JsonNode input = json.valueToTree(Map.of(
                "workOrder", Map.of("id", "wo-1", "branch", BRANCH, "title", "Fix", "description", "d"),
                "repository", Map.of("key", "github:acme/api", "defaultBranch", "main", "testCommand", testCommand,
                        "testReports", REPORTS, "sandboxImage", "alpine:latest"),
                "task", task, "artifacts", artifacts, "writesCode", task.equals("implementer"),
                "settings", Map.of("timeoutMinutes", 2, "cpus", 1, "memoryMb", 256)));
        return new ControlPlane.Job(UUID.randomUUID(), UUID.randomUUID(), kind, task, 1, input,
                json.readTree("{\"type\":\"object\"}"), BigDecimal.ONE);
    }

    JsonNode completed() {
        assertThat(received).as("the run failed: %s", new String(received.getOrDefault("fail", new byte[0])))
                .containsKey("complete");
        JsonNode completion = json.readTree(received.remove("complete"));
        return completion.get("output");
    }

    /** The implementer's work, as the next runs get it from the control plane. */
    void implement() {
        dispatcher.run(job("AGENT", "implementer", "true", Map.of()));
        assertThat(completed().get("commitMessage").asString()).isEqualTo("fix: it");
        bundle = received.remove("bundle");
        assertThat(bundle).as("the work uploaded as a bundle").isNotNull();
    }

    @Test
    void anAgentsWorkIsCommittedByTheWorkerAndCarriedToTheEvidenceRuns() {
        dispatcher.run(job("AGENT", "implementer", "true", Map.of()));
        assertThat(received).containsKey("bundle");
        JsonNode completion = json.readTree(received.get("complete"));
        assertThat(completion.get("costUsd").decimalValue()).isEqualByComparingTo("0.42");
        bundle = received.remove("bundle");
        received.clear();

        String tests = """
                mkdir -p target/surefire-reports && printf '<testsuite><testcase classname="A" name="ok"/>\
                <testcase classname="A" name="bad"><failure message="boom"/></testcase></testsuite>' \
                > target/surefire-reports/TEST-A.xml && test -f fix.txt && exit 1""";
        Map<String, Object> spec = Map.of("specifier", Map.of("command", """
                        test -f acceptance/accept.sh && mkdir -p target/surefire-reports && \
                        printf '<testsuite><testcase name="accepted"/></testsuite>' > target/surefire-reports/TEST-acc.xml""",
                "tests", List.of(Map.of("path", "acceptance/accept.sh", "content", "true"))));
        dispatcher.run(job("EVIDENCE", "run-tests", tests, spec));
        JsonNode evidence = completed();
        assertThat(evidence.get("diff")).isEqualTo(json.readTree("{\"files\":2,\"insertions\":2,\"deletions\":0}"));
        assertThat(evidence.get("tests")).isEqualTo(json.readTree(
                "{\"total\":2,\"failed\":1,\"errors\":0,\"skipped\":0,\"failures\":[\"A.bad: boom\"]}"));
        assertThat(evidence.get("acceptance").get("total").asInt()).isEqualTo(1);
        assertThat(evidence.get("acceptance").get("failed").asInt()).isZero();

        dispatcher.run(job("EVIDENCE", "secret-scan", "true", Map.of()));
        assertThat(completed().get("findings")).isEqualTo(json.readTree(
                "[{\"path\":\"config.txt\",\"line\":1,\"rule\":\"aws-access-key\"}]"));
    }

    @Test
    void thePushRunPublishesTheWorkBranch() throws Exception {
        implement();
        dispatcher.run(job("PUSH", "push-branch", "true", Map.of()));
        JsonNode pushed = completed();
        assertThat(pushed.get("branch").asString()).isEqualTo(BRANCH);
        try (Git git = Git.open(origin.toFile())) {
            assertThat(git.getRepository().resolve("refs/heads/" + BRANCH).name())
                    .isEqualTo(pushed.get("commit").asString());
        }
    }

    @Test
    void testsThatDoNotRunFailTheRunWithTheirOutput() {
        dispatcher.run(job("EVIDENCE", "run-tests", "echo no build tool here; exit 3", Map.of()));
        assertThat(received).doesNotContainKey("complete");
        assertThat(new String(received.get("fail"), StandardCharsets.UTF_8))
                .contains("did not run (exit 3", "no build tool here");

        received.clear();
        dispatcher.run(job("PUSH", "push-branch", "true", Map.of()));
        assertThat(new String(received.get("fail"), StandardCharsets.UTF_8)).contains("Nothing to push");
    }

    @Test
    void acceptanceTestsCannotBeWrittenOutsideTheRepository() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("repo"));
        Files.createSymbolicLink(root.resolve("out"), tmp);
        assertThat(RunExecutor.inside(root, "acceptance/a.sh")).isEqualTo(root.resolve("acceptance/a.sh"));
        assertThatThrownBy(() -> RunExecutor.inside(root, "../a.sh")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunExecutor.inside(root, "out/a.sh")).isInstanceOf(IllegalArgumentException.class);
    }
}
