package com.anode.factory.workorder;

import com.anode.factory.run.Run;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the outputs of a step's runs tell the transitions (fact names: workflow-definition schema). */
final class Facts {

    private static final List<String> RISKS = List.of("LOW", "MEDIUM", "HIGH");

    private Facts() {}

    /**
     * {@code highAboveFiles}: a change touching more files is HIGH risk. So are SECURITY work and
     * major dependency bumps, whatever risk the agents gave.
     */
    static Map<String, Object> of(List<Run> runs, String join, Map<String, Object> known, int highAboveFiles,
                                  JsonMapper json) {
        Map<String, Object> facts = new HashMap<>();
        List<String> verdicts = new java.util.ArrayList<>();
        for (Run run : runs) {
            JsonNode out = json.readTree(run.output());
            switch (run.outputSchema()) {
                case "classifier" -> {
                    String type = out.get("type").asString();
                    facts.put("type", type);
                    boolean high = type.equals("SECURITY")
                            || (type.equals("DEPENDENCY") && "major".equals(out.path("dependencyBump").asString("major")));
                    facts.put("risk", high ? "HIGH" : out.get("risk").asString());
                }
                case "analyst" -> {
                    facts.put("analysis.valid", out.get("valid").asBoolean());
                    JsonNode reproduced = out.get("reproduced");
                    if (reproduced != null && !reproduced.isNull()) {
                        facts.put("analysis.reproduced", reproduced.asBoolean());
                    }
                    facts.put("risk", higher((String) known.get("risk"), out.get("risk").asString()));
                }
                case "reviewer", "security-reviewer" -> verdicts.add(out.get("verdict").asString());
                case "run-tests" -> {
                    int files = out.get("diff").get("files").asInt();
                    facts.put("diff.files", files);
                    if (files > highAboveFiles) {
                        facts.put("risk", "HIGH");
                    }
                    facts.put("diff.empty", files == 0);
                    facts.put("tests.passed", passed(out.get("tests")));
                    if (out.has("acceptance")) {
                        facts.put("acceptance.passed", passed(out.get("acceptance")));
                    }
                }
                case "secret-scan" -> facts.put("secrets.found", !out.get("findings").isEmpty());
                case "feedback-triage" -> {
                    // feedback asking for no change (questions, out of scope, done) is answered, not coded
                    boolean changes = false;
                    for (JsonNode item : out.get("items")) {
                        changes |= "CHANGE".equals(item.get("decision").asString());
                    }
                    facts.put("triage.changes", changes);
                }
                case "update-branch" -> {
                    // the conflicting files: the next implementer run starts from the merge, markers in place
                    facts.put("branch.conflicted", !out.get("updated").asBoolean());
                    facts.put("branch.conflicts", json.convertValue(out.get("conflicts"), List.class));
                }
                case "judge" -> facts.put("eval.passed", passed(out.get("tests")));
                default -> {
                    // specifier, implementer: their outputs are artifacts, not facts
                }
            }
        }
        if (!verdicts.isEmpty()) {
            facts.put("verdict", joined(verdicts, join));
        }
        return facts;
    }

    private static boolean passed(JsonNode suite) {
        return suite.get("failed").asInt() == 0 && suite.get("errors").asInt() == 0;
    }

    private static String higher(String a, String b) {
        if (a == null) {
            return b;
        }
        return RISKS.indexOf(a) >= RISKS.indexOf(b) ? a : b;
    }

    /** PASS when the join holds over the reviewers' verdicts. */
    static String joined(List<String> verdicts, String join) {
        long passes = verdicts.stream().filter("PASS"::equals).count();
        boolean pass = switch (join == null ? "all" : join) {
            case "any" -> passes > 0;
            case "majority" -> passes * 2 > verdicts.size();
            default -> passes == verdicts.size();
        };
        return pass ? "PASS" : "REVISE";
    }
}
