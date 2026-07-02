package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommerceToolsTest {

    @Test
    void everyManagedCommerceToolIsClientInvocable() {
        for (ToolDescriptor d : CommerceTools.managedDescriptors()) {
            assertTrue(d.policy().clientInvocable(),
                    d.id().value() + " must be tappable via the action channel");
        }
    }

    @Test
    void addToCartHasNoPriceField() {
        ToolDescriptor add = CommerceTools.managedDescriptors().stream()
                .filter(d -> d.id().value().equals(CommerceTools.ADD_TO_CART))
                .findFirst().orElseThrow();
        assertFalse(add.inputSchemaJson().contains("price"),
                "add_to_cart must never accept a price — the catalog is authoritative (#1)");
    }

    @Test
    void toolPolicyDefaultsToNotClientInvocable() {
        assertFalse(ToolPolicy.defaults().clientInvocable());
        // Back-compat 4-arg constructor keeps existing tools locked to the model path.
        assertFalse(new ToolPolicy(Duration.ofSeconds(10), 0, true, false).clientInvocable());
    }
}
