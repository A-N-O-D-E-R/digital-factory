package com.anode.factory.workflow;

import java.util.Map;
import java.util.Set;

/** Agent roles a workflow may use. */
// ponytail: built-in roles only; custom review roles (spec story 45) will come from the agent_roles table
public final class AgentRoles {

    /** Role name to whether its runs modify the repository. */
    private static final Map<String, Boolean> WRITES_CODE = Map.of(
            "classifier", false,
            "analyst", false,
            "specifier", false,
            "implementer", true,
            "reviewer", false,
            "security-reviewer", false,
            "feedback-triage", false);

    private AgentRoles() {}

    public static Set<String> names() {
        return WRITES_CODE.keySet();
    }

    public static boolean exists(String role) {
        return WRITES_CODE.containsKey(role);
    }

    public static boolean writesCode(String role) {
        return WRITES_CODE.getOrDefault(role, false);
    }
}
