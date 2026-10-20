package com.anode.factory.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes one claimed run. Agent runs execute the runner in a sandbox given the work tree; the
 * worker, not the agent, commits what it left and uploads it as the run's bundle. Evidence runs
 * execute the repository's commands in a sandbox without agent nor credential.
 */
@Component
class RunExecutor {

    /** What a run produced: its output (null when it failed) or why it failed, and what it cost. */
    record Outcome(JsonNode output, String error, BigDecimal costUsd, ControlPlane.Usage usage) {
        Outcome(JsonNode output, String error, BigDecimal costUsd) {
            this(output, error, costUsd, null);
        }

        static Outcome failed(String error, BigDecimal costUsd) {
            return new Outcome(null, error, costUsd);
        }
    }

    private static final int MAX_PATCH = 400_000;

    private final ControlPlane controlPlane;
    private final Sandbox sandbox;
    private final WorkerProperties properties;
    private final JsonMapper json;

    RunExecutor(ControlPlane controlPlane, Sandbox sandbox, WorkerProperties properties, JsonMapper json) {
        this.controlPlane = controlPlane;
        this.sandbox = sandbox;
        this.properties = properties;
        this.json = json;
    }

    Outcome execute(ControlPlane.Job job, Path dir, BooleanSupplier cancelled) {
        JsonNode input = job.input();
        JsonNode repository = input.get("repository");
        byte[] bundle = controlPlane.bundle(job.runId()).getBody();
        try (Workspace workspace = Workspace.clone(controlPlane.credential(job.runId()),
                repository.get("defaultBranch").asString(), input.get("workOrder").get("branch").asString(), bundle,
                dir)) {
            return switch (job.kind()) {
                case "AGENT" -> agent(job, workspace, dir, cancelled);
                case "UPDATE" -> update(job, workspace);
                case "PUSH" -> new Outcome(json.valueToTree(
                        Map.of("branch", workspace.branch(), "commit", workspace.push())), null, null);
                case "EVIDENCE" -> switch (job.task()) {
                    case "run-tests" -> tests(job, workspace, cancelled);
                    case "secret-scan" -> new Outcome(json.valueToTree(
                            Map.of("findings", SecretScan.scan(workspace.diff()))), null, null);
                    case "judge" -> judge(job, workspace, cancelled);
                    default -> Outcome.failed("This worker does not know the action " + job.task(), null);
                };
                default -> Outcome.failed("This worker does not know runs of kind " + job.kind(), null);
            };
        }
    }

    /** The default branch merged into the work branch: committed when clean, the conflicts listed otherwise. */
    private Outcome update(ControlPlane.Job job, Workspace workspace) {
        List<String> conflicts = workspace.mergeDefaultBranch();
        if (conflicts.isEmpty() && workspace.commitAll("Merge the default branch")) {
            controlPlane.upload(job.runId(), workspace.bundle());
        }
        return new Outcome(json.valueToTree(Map.of("updated", conflicts.isEmpty(), "conflicts", conflicts)), null, null);
    }

    /**
     * The runner reads /job/job.json and writes /out/result.json: {output, costUsd} or {error, costUsd}.
     * A code writer whose branch conflicts with the default branch starts from the merge, markers in
     * place; what it leaves is committed as the merge.
     */
    private Outcome agent(ControlPlane.Job job, Workspace workspace, Path dir, BooleanSupplier cancelled) {
        boolean writes = job.input().path("writesCode").asBoolean(false);
        List<String> conflicts = writes && job.input().path("facts").path("branch.conflicted").asBoolean(false)
                ? workspace.mergeDefaultBranch() : List.of();
        Path jobDir = directory(dir.resolve("job"));
        Path out = directory(dir.resolve("out"));
        // git works in the sandbox, on the read-only git directory: it can read history, not change it
        write(workspace.tree().resolve(".git"), "gitdir: /repo.git\n");
        write(jobDir.resolve("job.json"), json.writeValueAsString(Map.of(
                "task", job.task(), "input", job.input(), "outputSchema", job.outputSchema(),
                "budgetUsd", job.budgetUsd() == null ? BigDecimal.ZERO : job.budgetUsd())));
        JsonNode settings = job.input().path("settings");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"); // nothing but the API through the egress proxy
        if (properties.anthropicApiKey() != null) {
            env.put("ANTHROPIC_API_KEY", properties.anthropicApiKey());
        }
        Sandbox.Exit exit = sandbox.run(new Sandbox.Spec(image(job), properties.agentCommand(), env, List.of(
                new Sandbox.Mount(workspace.tree(), "/workspace", true),
                new Sandbox.Mount(workspace.gitDir(), "/repo.git", false),
                new Sandbox.Mount(jobDir, "/job", false),
                new Sandbox.Mount(out, "/out", true)),
                settings.path("cpus").asDouble(2.0), settings.path("memoryMb").asInt(4096),
                Duration.ofMinutes(settings.path("timeoutMinutes").asInt(30))), cancelled);
        Path resultFile = out.resolve("result.json");
        if (!Files.isRegularFile(resultFile)) {
            return Outcome.failed(exit.timedOut() ? "The agent timed out" : "The agent left no result (exit "
                    + exit.code() + "):\n" + exit.logs(), null);
        }
        JsonNode result = json.readTree(read(resultFile));
        BigDecimal cost = result.hasNonNull("costUsd") ? result.get("costUsd").decimalValue() : null;
        JsonNode used = result.path("usage");
        ControlPlane.Usage usage = used.isObject() ? new ControlPlane.Usage(used.path("model").asString(null),
                used.path("inputTokens").asLong(0), used.path("outputTokens").asLong(0)) : null;
        if (!result.hasNonNull("output")) {
            return new Outcome(null, result.path("error").asString("The agent produced no output"), cost, usage);
        }
        JsonNode output = result.get("output");
        for (String conflict : conflicts) {
            Path file = workspace.tree().resolve(conflict);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && read(file).contains("<<<<<<< ")) {
                return new Outcome(null, "Conflict left unresolved in " + conflict, cost, usage);
            }
        }
        if (writes && workspace.commitAll(output.path("commitMessage").asString(job.task() + " changes"))) {
            controlPlane.upload(job.runId(), workspace.bundle());
        }
        return new Outcome(output, null, cost, usage);
    }

    /** The repository's tests, then the hidden acceptance tests if the specifier wrote some. */
    private Outcome tests(ControlPlane.Job job, Workspace workspace, BooleanSupplier cancelled) {
        Workspace.DiffStats diff = workspace.diffStats();
        JsonNode repository = job.input().get("repository");
        String reports = repository.get("testReports").asString();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("diff", diff);
        String patch = workspace.diff();
        // what reviewers and the UI read; past the cap they have the branch
        evidence.put("patch", patch.length() > MAX_PATCH ? patch.substring(0, MAX_PATCH) + "\n[… cut]" : patch);
        Object tests = suite(job, workspace, repository.get("testCommand").asString(), reports, cancelled, "tests");
        if (tests instanceof String error) {
            return Outcome.failed(error, null);
        }
        evidence.put("tests", tests);
        JsonNode spec = job.input().path("artifacts").path("specifier");
        if (!spec.isMissingNode()) {
            for (Path report : JUnitReports.find(workspace.tree(), reports)) {
                delete(report); // the acceptance run's reports only
            }
            for (JsonNode test : spec.get("tests")) {
                write(inside(workspace.tree(), test.get("path").asString()), test.get("content").asString());
            }
            // ponytail: counts every report the acceptance command writes; filter by the spec's files if it runs more
            Object acceptance = suite(job, workspace, spec.get("command").asString(), reports, cancelled,
                    "acceptance tests");
            if (acceptance instanceof String error) {
                return Outcome.failed(error, null);
            }
            evidence.put("acceptance", acceptance);
        }
        return new Outcome(json.valueToTree(evidence), null, null);
    }

    private Outcome judge(ControlPlane.Job job, Workspace workspace, BooleanSupplier cancelled) {
        Object tests = suite(job, workspace, job.input().get("judge").get("command").asString(),
                job.input().get("repository").get("testReports").asString(), cancelled, "hidden tests");
        return tests instanceof String error ? Outcome.failed(error, null)
                : new Outcome(json.valueToTree(Map.of("tests", tests)), null, null);
    }

    /** The suite a command's reports describe, or why there is none (an error message). */
    private Object suite(ControlPlane.Job job, Workspace workspace, String command, String reports,
                         BooleanSupplier cancelled, String what) {
        Sandbox.Exit exit = sandbox.run(new Sandbox.Spec(image(job), List.of("sh", "-c", command), Map.of(),
                List.of(new Sandbox.Mount(workspace.tree(), "/workspace", true)), properties.evidenceCpus(),
                properties.evidenceMemoryMb(), properties.evidenceTimeout()), cancelled);
        if (exit.timedOut()) {
            return "The " + what + " timed out";
        }
        List<Path> files = JUnitReports.find(workspace.tree(), reports);
        if (files.isEmpty() && exit.code() != 0) {
            return "The " + what + " did not run (exit " + exit.code() + ", no report):\n" + exit.logs();
        }
        return JUnitReports.parse(files);
    }

    private static String image(ControlPlane.Job job) {
        return job.input().get("repository").get("sandboxImage").asString();
    }

    /**
     * {@code relative} under {@code root}, its directories created, or refused: paths come from an
     * agent, and so do the tree's links (a directory may be a link out of it).
     */
    static Path inside(Path root, String relative) {
        try {
            Path path = root.resolve(relative).normalize();
            if (!path.startsWith(root) || path.equals(root)) {
                throw new IllegalArgumentException("Not a path inside the repository: " + relative);
            }
            Path parent = Files.createDirectories(path.getParent());
            if (!parent.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("Not a path inside the repository: " + relative);
            }
            Files.deleteIfExists(path); // a link there is replaced, not followed
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path directory(Path dir) {
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
