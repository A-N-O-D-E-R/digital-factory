package com.anode.factory;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Application modules only use each other's published API. */
class ModularityTest {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(FactoryApplication.class).verify();
    }
}
