package com.anode.factory.forge;

import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.ForgeType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The configured forges; a repository's forge must be configured for the factory to work on it. */
@Component
public class Forges {

    private final Map<ForgeType, Forge> byType = new EnumMap<>(ForgeType.class);

    Forges(List<Forge> forges) {
        forges.forEach(forge -> byType.put(forge.type(), forge));
    }

    public Forge of(CodeRepository repository) {
        Forge forge = byType.get(repository.forge());
        if (forge == null) {
            throw new IllegalStateException("No " + repository.forge() + " integration is configured");
        }
        return forge;
    }
}
