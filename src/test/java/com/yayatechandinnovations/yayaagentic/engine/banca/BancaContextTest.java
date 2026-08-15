package com.yayatechandinnovations.yayaagentic.engine.banca;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext.LinkState;
import com.yayatechandinnovations.yayaagentic.testsupport.JsonWorkingMemory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BancaContext round-trips through the JSON WorkingMemory boundary (design §2). */
class BancaContextTest {

    private final JsonWorkingMemory wm = new JsonWorkingMemory(new ObjectMapper());
    private final Ids.SessionId session = new Ids.SessionId("s1");

    @Test
    void commitAndReadRoundTrips() {
        BancaContext.commit(wm, session,
                new BancaContext("LAMINA01", "org-9", "Banca La Mina", LinkState.ACTIVE, Instant.parse("2026-08-15T12:00:00Z")));
        BancaContext read = BancaContext.read(wm, session).orElseThrow();
        assertEquals("LAMINA01", read.installCode());
        assertEquals("org-9", read.orgId());
        assertEquals("Banca La Mina", read.displayName());
        assertEquals(LinkState.ACTIVE, read.linked());
        assertTrue(BancaContext.hasActiveContext(wm, session));
    }

    @Test
    void pendingIsNotActive() {
        BancaContext.commit(wm, session,
                new BancaContext("X", "o", "n", LinkState.PENDING, Instant.parse("2026-08-15T12:00:00Z")));
        assertFalse(BancaContext.hasActiveContext(wm, session));
    }

    @Test
    void playCredentialNeverPersistedToWorkingMemory() {
        BancaContext.commit(wm, session,
                new BancaContext("X", "o", "n", LinkState.ACTIVE, Instant.parse("2026-08-15T12:00:00Z")));
        Object slot = wm.get(session).get(BancaContext.SLOT);
        // The serialized map carries the field as null for shape fidelity, never a token.
        assertTrue(slot instanceof java.util.Map);
        assertNull(((java.util.Map<?, ?>) slot).get("playCredential"));
    }

    @Test
    void offeredCodesGateSelection() {
        BancaContext.rememberOffered(wm, session, List.of("A1", "B2"));
        assertTrue(BancaContext.wasOffered(wm, session, "A1"));
        assertFalse(BancaContext.wasOffered(wm, session, "ZZ"));
        assertFalse(BancaContext.wasOffered(wm, session, null));
    }
}
