package com.anode.factory.settings;

import com.anode.factory.repository.Repositories;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Settings in layers: the built-in defaults ({@code settings/defaults.json}), the global layer, then
 * a repository's layer, each applied as a JSON Merge Patch. Only merged settings matching {@code
 * contracts/settings.schema.json} are ever saved; work orders freeze them at creation.
 */
@Service
public class Settings {

    static final String GLOBAL = "global";
    private static final String REPOSITORY = "repository:";

    /** A layer, and the settings it gives once merged. */
    public record Layer(String scope, JsonNode document, JsonNode effective, String updatedBy, Instant updatedAt) {}

    private final SettingsLayerRepository layers;
    private final SettingsChangeRepository changes;
    private final Repositories repositories;
    private final JsonMapper json;
    private final Clock clock;
    private final JsonNode defaults;
    private final Schema schema;

    Settings(SettingsLayerRepository layers, SettingsChangeRepository changes, Repositories repositories,
             JsonMapper json, Clock clock) {
        this.layers = layers;
        this.changes = changes;
        this.repositories = repositories;
        this.json = json;
        this.clock = clock;
        try (InputStream schemaIn = getClass().getResourceAsStream("/contracts/settings.schema.json");
             InputStream defaultsIn = getClass().getResourceAsStream("/settings/defaults.json")) {
            var config = SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build();
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                    builder -> builder.schemaRegistryConfig(config)).getSchema(schemaIn, InputFormat.JSON);
            this.defaults = json.readTree(defaultsIn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> errors = errors(defaults);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid built-in settings: " + errors);
        }
    }

    public JsonNode defaults() {
        return defaults.deepCopy();
    }

    /** The global layer when {@code repository} is null. */
    public Layer layer(String repository) {
        String scope = scope(repository);
        Optional<SettingsLayer> layer = layers.findById(scope);
        return new Layer(scope, document(scope), merged(repository),
                layer.map(SettingsLayer::updatedBy).orElse(null), layer.map(SettingsLayer::updatedAt).orElse(null));
    }

    /** What a work order on {@code repository} runs with; refused if a later change broke it. */
    public JsonNode effective(String repository) {
        JsonNode effective = merged(repository);
        List<String> errors = errors(effective);
        if (!errors.isEmpty()) {
            throw new InvalidSettingsException(errors);
        }
        return effective;
    }

    @Transactional
    public Layer update(String repository, JsonNode document, String reason, String by) {
        return replace(scope(repository), document, reason, by);
    }

    /** Puts the layer back as it was before {@code changeId}; recorded as a change itself. */
    @Transactional
    public Layer restore(UUID changeId, String reason, String by) {
        SettingsChange change = changes.findById(changeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No settings change " + changeId));
        return replace(change.scope(), json.readTree(change.before()),
                "restore of " + changeId + (reason == null || reason.isBlank() ? "" : ": " + reason), by);
    }

    public List<SettingsChange> changes(String repository) {
        return changes.findByScopeOrderByChangedAtDesc(scope(repository));
    }

    private Layer replace(String scope, JsonNode document, String reason, String by) {
        if (document == null || !document.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The settings document must be an object");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required");
        }
        JsonNode before = document(scope);
        String repository = scope.startsWith(REPOSITORY) ? scope.substring(REPOSITORY.length()) : null;
        List<String> errors = new ArrayList<>();
        if (repository == null) {
            // every repository builds on the global layer: none of them may break
            JsonNode global = MergePatch.apply(defaults, document);
            errors.addAll(errors(global));
            for (SettingsLayer layer : layers.findByScopeStartingWith(REPOSITORY)) {
                errors(MergePatch.apply(global, json.readTree(layer.document())))
                        .forEach(error -> errors.add(layer.scope() + " " + error));
            }
        } else {
            errors.addAll(errors(MergePatch.apply(MergePatch.apply(defaults, document(GLOBAL)), document)));
        }
        if (!errors.isEmpty()) {
            throw new InvalidSettingsException(errors);
        }
        Instant now = clock.instant();
        SettingsLayer layer = layers.findById(scope).orElseGet(() -> new SettingsLayer(scope));
        layer.update(json.writeValueAsString(document), by, now);
        layers.save(layer);
        changes.save(new SettingsChange(scope, json.writeValueAsString(before), json.writeValueAsString(document),
                reason, by, now));
        return layer(repository);
    }

    private String scope(String repository) {
        if (repository == null) {
            return GLOBAL;
        }
        return REPOSITORY + repositories.get(repository).key();
    }

    private JsonNode document(String scope) {
        return layers.findById(scope).map(layer -> json.readTree(layer.document()))
                .orElseGet(JsonNodeFactory.instance::objectNode);
    }

    private JsonNode merged(String repository) {
        JsonNode global = MergePatch.apply(defaults, document(GLOBAL));
        return repository == null ? global : MergePatch.apply(global, document(scope(repository)));
    }

    /** Schema violations, then roles naming a model missing from the catalog. */
    private List<String> errors(JsonNode effective) {
        List<String> errors = new ArrayList<>(schema.validate(json.writeValueAsString(effective), InputFormat.JSON)
                .stream().map(error -> error.getInstanceLocation() + ": " + error.getMessage()).toList());
        if (errors.isEmpty()) {
            for (var role : effective.get("roles").properties()) {
                JsonNode model = role.getValue().get("model");
                if (model != null && !effective.get("models").has(model.asString())) {
                    errors.add("/roles/" + role.getKey() + "/model: unknown model '" + model.asString() + "'");
                }
            }
        }
        return errors;
    }
}
