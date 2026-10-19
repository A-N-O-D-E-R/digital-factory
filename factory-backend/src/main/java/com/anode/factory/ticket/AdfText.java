package com.anode.factory.ticket;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The text of an Atlassian Document Format document: paragraphs on their own lines, list items dashed. */
// ponytail: text only (no tables, links' targets, marks); html -> markdown with flexmark if agents need more
final class AdfText {

    private static final Set<String> BLOCKS = Set.of("paragraph", "heading", "codeBlock", "blockquote", "rule",
            "panel", "mediaSingle");

    private AdfText() {}

    static String of(Object document) {
        StringBuilder text = new StringBuilder();
        append(document, text);
        return text.toString().strip();
    }

    private static void append(Object node, StringBuilder text) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        Object type = map.get("type");
        if ("text".equals(type)) {
            text.append(map.get("text"));
        } else if ("hardBreak".equals(type)) {
            text.append('\n');
        } else if ("listItem".equals(type)) {
            text.append("- ");
        }
        if (map.get("content") instanceof List<?> children) {
            children.forEach(child -> append(child, text));
        }
        if (BLOCKS.contains(type) && !text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
    }
}
