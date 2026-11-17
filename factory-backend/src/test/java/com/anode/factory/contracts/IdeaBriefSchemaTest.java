package com.anode.factory.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The idea-brief contract: what idea-generator writes, optionally with ai-scientist's research, and what productDesigner imports. */
class IdeaBriefSchemaTest {

    private static final Schema SCHEMA = load("/contracts/idea-brief.schema.json");

    private static Schema load(String path) {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> { })
                .getSchema(read(path), InputFormat.JSON);
    }

    private static String read(String path) {
        try (var in = IdeaBriefSchemaTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> violations(String json) {
        return SCHEMA.validate(json, InputFormat.JSON).stream().map(Object::toString).toList();
    }

    private static final String BRIEF = """
            {"name":"X","slug":"x","oneLiner":"o","problem":"p","audience":"a","mvp":["m"],"outOfScope":[],"stack":"s",
             "firstSteps":["f"],"openQuestions":["q"],"risks":["r"]}""";

    @Test
    void theExampleWithResearchMatches() {
        assertThat(violations(read("/contracts/examples/idea-brief.example.json"))).isEmpty();
    }

    @Test
    void anIdeaJsonStraightFromIdeaGeneratorMatchesWithoutResearch() {
        // idea-generator's Project.writeProject: createdAt, goal, idea (full record, nulls included), lineage, brief
        assertThat(violations("""
                {"createdAt":"2026-01-01T00:00:00Z","goal":"g",
                 "idea":{"id":1,"boardId":1,"title":"T","pitch":null,"kind":"seed","sourceUrl":null,"x":1.5,"y":2.0,
                         "originality":null,"feasibility":null,"impact":null,"reason":null,"judgedAt":null,"medalOverride":null,
                         "generation":0,"projectPath":null,"originIdeaId":null,"originBoardId":null,"originBoardTitle":null,
                         "keywords":[],"createdAt":"2026-01-01T00:00:00Z","elo":null,"duels":0},
                 "lineage":[],"brief":%s}""".formatted(BRIEF))).isEmpty();
    }

    @Test
    void theBriefIsComplete() {
        assertThat(violations("""
                {"goal":"g","idea":{"title":"T"},"lineage":[],"brief":{"name":"X"}}""")).isNotEmpty();
        assertThat(violations("""
                {"goal":"g","idea":{"title":"T"},"lineage":[]}""")).isNotEmpty();
    }

    @Test
    void theResearchBlockIsClosedAndItsVerdictIsAnEnum() {
        String base = """
                {"goal":"g","idea":{"title":"T"},"lineage":[],"brief":%s,"research":%%s}""".formatted(BRIEF);
        assertThat(violations(base.formatted("{\"noveltyVerdict\":\"novel\",\"hypotheses\":[\"h\"]}"))).isEmpty();
        assertThat(violations(base.formatted("{}"))).isEmpty();
        assertThat(violations(base.formatted("{\"noveltyVerdict\":\"maybe\"}"))).isNotEmpty();
        assertThat(violations(base.formatted("{\"surprise\":1}"))).isNotEmpty();
    }
}
