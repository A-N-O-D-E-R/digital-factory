package com.anode.factory.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdfTextTest {

    static Map<String, Object> node(String type, Object... content) {
        return Map.of("type", type, "content", List.of(content));
    }

    static Map<String, Object> text(String text) {
        return Map.of("type", "text", "text", text);
    }

    @Test
    void paragraphsOnTheirOwnLinesListItemsDashed() {
        Object document = node("doc",
                node("paragraph", text("Rounding is "), text("off"), Map.of("type", "hardBreak"), text("by one.")),
                node("bulletList", node("listItem", node("paragraph", text("a"))),
                        node("listItem", node("paragraph", text("b")))));
        assertThat(AdfText.of(document)).isEqualTo("Rounding is off\nby one.\n- a\n- b");
        assertThat(AdfText.of(null)).isEmpty();
    }
}
