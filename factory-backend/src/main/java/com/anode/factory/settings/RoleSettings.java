package com.anode.factory.settings;

import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The settings of one run of a role: the role's over the default, the step's over both, the model resolved. */
public final class RoleSettings {

    private RoleSettings() {}

    /**
     * {@code effective} as frozen on the work order; {@code stepOverrides} from the workflow step.
     *
     * @throws IllegalArgumentException when the model is not in the catalog
     */
    public static JsonNode of(JsonNode effective, String role, Map<String, Object> stepOverrides, JsonMapper json) {
        JsonNode roles = effective.get("roles");
        JsonNode merged = MergePatch.apply(roles.get("default"), roles.path(role).isObject() ? roles.get(role)
                : json.createObjectNode());
        if (stepOverrides != null && !stepOverrides.isEmpty()) {
            merged = MergePatch.apply(merged, json.valueToTree(stepOverrides));
        }
        String model = merged.get("model").asString();
        JsonNode modelId = effective.get("models").get(model);
        if (modelId == null) {
            throw new IllegalArgumentException("unknown model '" + model + "' for role " + role);
        }
        return ((ObjectNode) merged).put("modelId", modelId.asString());
    }
}
