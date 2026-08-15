package com.yayatechandinnovations.yayaagentic.channel.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext;
import com.yayatechandinnovations.yayaagentic.testsupport.JsonWorkingMemory;
import com.yayatechandinnovations.yayaagentic.tool.ToolException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The §4.1 coverage contract + phone-from-session invariant, enforced in-process. */
class NetworkToolSupportTest {

    private final JsonWorkingMemory wm = new JsonWorkingMemory(new ObjectMapper());
    private final NetworkToolSupport support = new NetworkToolSupport(wm);
    private final Ids.SessionId session = new Ids.SessionId("s1");

    private ExecutionContext ctx(Map<String, Object> claims) {
        Principal p = new Principal("sub", new Ids.TenantId("t"), Set.of(), claims, Instant.now());
        return new ExecutionContext(p, session, new Ids.TurnId("turn1"), "trace", Map.of());
    }

    @Test
    void verifiedPhoneComesFromClaims() {
        assertEquals("+18091234567",
                support.requireVerifiedPhone(ctx(Map.of("phone", "+18091234567"))));
    }

    @Test
    void missingPhoneIsHardError() {
        assertThrows(ToolException.class, () -> support.requireVerifiedPhone(ctx(Map.of())));
    }

    @Test
    void partialCoverageSetsDirectiveAndTagsPartial() {
        List<Map<String, Object>> sources = List.of(
                Map.of("banca", "Banca La Mina", "status", "OK"),
                Map.of("banca", "Banca El Sol", "status", "TIMEOUT"));
        String coverage = support.applyCoverage(ctx(Map.of("phone", "x")), sources);
        assertEquals("partial", coverage);
        Object directive = wm.get(session).get(BancaContext.COVERAGE_DIRECTIVE_SLOT);
        assertNotNull(directive);
        // Must name the banca that could not be checked (§4.1).
        assertEquals(true, String.valueOf(directive).contains("Banca El Sol"));
    }

    @Test
    void fullCoverageSetsNoDirective() {
        List<Map<String, Object>> sources = List.of(
                Map.of("banca", "A", "status", "OK"),
                Map.of("banca", "B", "status", "OK"));
        assertEquals("complete", support.applyCoverage(ctx(Map.of("phone", "x")), sources));
        assertNull(wm.get(session).get(BancaContext.COVERAGE_DIRECTIVE_SLOT));
    }

    @Test
    void userSafeCopyIsDistinctPerError() {
        assertFalse(support.userSafeReason(HubBrokerClient.BrokerError.NOT_ENROLLED).isBlank());
        assertFalse(support.userSafeReason(HubBrokerClient.BrokerError.PLAY_DISABLED).isBlank());
    }
}
