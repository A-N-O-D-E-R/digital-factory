package com.anode.factory.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The fixture set, read from {@code factory.evals.fixtures}. */
@Component
class Fixtures {

    private final List<Fixture> all;

    Fixtures(@Value("${factory.evals.fixtures:classpath:evals/fixtures.json}") Resource file, JsonMapper json) {
        try (InputStream in = file.getInputStream()) {
            this.all = json.readValue(in, new TypeReference<List<Fixture>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the eval fixtures " + file, e);
        }
        all.forEach(fixture -> {
            if (!List.of("success", "refusal").contains(fixture.expect())
                    || (fixture.expectsSuccess() && fixture.judge() == null)) {
                throw new IllegalStateException("Fixture " + fixture.id() + ": expect success (with a judge) or refusal");
            }
        });
    }

    List<Fixture> all() {
        return all;
    }
}
