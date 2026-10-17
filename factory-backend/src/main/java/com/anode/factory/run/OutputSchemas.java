package com.anode.factory.run;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The JSON Schema every run output must match ({@code contracts/outputs/<task>.schema.json}): the
 * only source of these schemas, sent to the worker with each job and enforced again on completion.
 */
@Component
public class OutputSchemas {

    private record Loaded(String text, Schema schema) {}

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build()));
    private final Map<String, Loaded> byTask = new ConcurrentHashMap<>();

    private Loaded load(String task) {
        return byTask.computeIfAbsent(task, name -> {
            try (InputStream in = getClass().getResourceAsStream("/contracts/outputs/" + name + ".schema.json")) {
                if (in == null) {
                    throw new IllegalArgumentException("No output schema for '" + name + "'");
                }
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return new Loaded(text, registry.getSchema(text, InputFormat.JSON));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public boolean exists(String task) {
        return getClass().getResource("/contracts/outputs/" + task + ".schema.json") != null;
    }

    /** The schema text, for the worker (the agent SDK enforces it as structured output). */
    public String text(String task) {
        return load(task).text();
    }

    /** What is wrong with {@code outputJson}; empty when it matches. */
    public List<String> violations(String task, String outputJson) {
        if (outputJson == null) {
            return List.of("no output");
        }
        try {
            return load(task).schema().validate(outputJson, InputFormat.JSON).stream()
                    .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                    .toList();
        } catch (RuntimeException unparsable) {
            return List.of("output is not JSON: " + unparsable.getMessage());
        }
    }
}
