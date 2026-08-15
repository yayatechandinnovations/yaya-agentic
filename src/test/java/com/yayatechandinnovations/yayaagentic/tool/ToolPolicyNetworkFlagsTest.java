package com.yayatechandinnovations.yayaagentic.tool;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The network play-path flags are additive and default false (design §4.1). */
class ToolPolicyNetworkFlagsTest {

    @Test
    void defaultsAndBackCompatConstructorsLeaveFlagsFalse() {
        assertFalse(ToolPolicy.defaults().requiresBancaContext());
        assertFalse(ToolPolicy.defaults().requiresApprovedQuote());

        ToolPolicy sixArg = new ToolPolicy(Duration.ofSeconds(1), 0, true, false, false, "ask");
        assertFalse(sixArg.requiresBancaContext());
        assertFalse(sixArg.requiresApprovedQuote());

        ToolPolicy fourArg = new ToolPolicy(Duration.ofSeconds(1), 0, true, true);
        assertFalse(fourArg.requiresBancaContext());
        assertFalse(fourArg.requiresApprovedQuote());
    }

    @Test
    void fullConstructorCarriesFlags() {
        ToolPolicy p = new ToolPolicy(Duration.ofSeconds(1), 0, false, true, false, "ask", true, true);
        assertTrue(p.requiresBancaContext());
        assertTrue(p.requiresApprovedQuote());
    }
}
