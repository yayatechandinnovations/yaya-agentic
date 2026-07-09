package com.yayatechandinnovations.yayasales;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Design §6: modular monolith — module calls only through published module
 * APIs, dependencies acyclic. Fails the build on any boundary violation, so
 * "inventory reaches into orders' internals" is a compile-time-ish error, not
 * a code-review hope.
 */
class ModularityTest {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(YayaSalesApplication.class).verify();
    }
}
