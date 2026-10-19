package com.anode.factory.settings;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** JSON Merge Patch (RFC 7396): objects merged, anything else (arrays included) replaced, null removes. */
final class MergePatch {

    private MergePatch() {}

    static JsonNode apply(JsonNode target, JsonNode patch) {
        if (!patch.isObject()) {
            return patch;
        }
        ObjectNode result = target != null && target.isObject()
                ? ((ObjectNode) target).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        for (var member : patch.properties()) {
            if (member.getValue().isNull()) {
                result.remove(member.getKey());
            } else {
                result.set(member.getKey(), apply(result.get(member.getKey()), member.getValue()));
            }
        }
        return result;
    }
}
