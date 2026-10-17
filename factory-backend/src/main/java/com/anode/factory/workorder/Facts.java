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

    static Map<String, Object> of(List<Run> runs, String join, Map<String, Object> known, JsonMapper json) {
        Map<String, Object> facts = new HashMap<>();
        List<String> verdicts = new java.util.ArrayList<>();
        for (Run run : runs) {
            JsonNode out = json.readTree(run.output());
            switch (run.task()) {
                case "classifier" -> {
                    facts.put("type", out.get("type").asString());
                    facts.put("risk", out.get("risk").asString());
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
                    facts.put("diff.empty", files == 0);
                    facts.put("tests.passed", passed(out.get("tests")));
                    if (out.has("acceptance")) {
                        facts.put("acceptance.passed", passed(out.get("acceptance")));
                    }
                }
                case "secret-scan" -> facts.put("secrets.found", !out.get("findings").isEmpty());
                default -> {
                    // specifier, implementer, feedback-triage: their outputs are artifacts, not facts
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
