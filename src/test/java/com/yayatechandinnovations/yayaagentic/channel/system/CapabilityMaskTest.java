package com.yayatechandinnovations.yayaagentic.channel.system;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** §9.1 Rule 2: the mask is deny-by-default and always keeps the reply reachable. */
class CapabilityMaskTest {

    @Test
    void responseOnlyIsDenyByDefault() {
        CapabilityMask m = CapabilityMask.responseOnly("system.reply");
        assertThat(m.allow()).containsExactly("system.reply");
        assertThat(m.allowReads()).isTrue();
        assertThat(m.requireConfirmForMutating()).isTrue();
    }

    @Test
    void effectiveDefaultsToDenyByDefaultWhenUnconfigured() {
        CapabilityMask m = CapabilityMask.effective(null, "system.reply");
        assertThat(m.allow()).contains("system.reply");
    }

    @Test
    void effectiveAlwaysKeepsResponseToolReachable() {
        // Operator widened the mask but forgot the response tool — it's added back.
        CapabilityMask configured = new CapabilityMask(Set.of("search_orders"), true, false);
        CapabilityMask m = CapabilityMask.effective(configured, "system.reply");
        assertThat(m.allow()).contains("search_orders", "system.reply");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toWorkingMemoryCarriesTheShapeTheEngineReads() {
        var wm = CapabilityMask.responseOnly("system.reply").toWorkingMemory();
        assertThat(wm).containsKeys("allow", "allowReads", "requireConfirm");
        java.util.List<String> allow = (java.util.List<String>) wm.get("allow");
        assertThat(allow).contains("system.reply");
    }
}
