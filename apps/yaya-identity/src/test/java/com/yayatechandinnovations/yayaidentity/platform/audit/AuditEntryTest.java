package com.yayatechandinnovations.yayaidentity.platform.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Non-negotiable #3, enforced at the earliest point: a DENY must carry two
 * distinct reasons. This is the platform-level guard; the PDP (I3) adds the
 * build-failing lint over every construction site (PRD M5).
 */
class AuditEntryTest {

    @Test
    void denyWithTwoDistinctReasonsIsAccepted() {
        assertDoesNotThrow(() -> AuditEntry.deny("org1", "u1", "sales.order:refund", "order:o1",
                "You can't refund orders over $500.",
                "policy refund-cap denied: 750 > 500 for role store-manager"));
    }

    @Test
    void denyWithIdenticalReasonsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AuditEntry.deny("org1", "u1", "a.b:c", "r",
                        "same reason", "same reason"));
    }

    @Test
    void denyMissingAuditReasonIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AuditEntry.deny("org1", "u1", "a.b:c", "r", "user only", null));
    }

    @Test
    void permitNeedsNoReasons() {
        AuditEntry e = AuditEntry.permit("org1", "u1", "a.b:c", "r");
        assertEquals(AuditEntry.Decision.PERMIT, e.decision());
    }

    @Test
    void eventRequiresAType() {
        assertThrows(IllegalArgumentException.class,
                () -> AuditEntry.event("", "u1", null));
    }
}
