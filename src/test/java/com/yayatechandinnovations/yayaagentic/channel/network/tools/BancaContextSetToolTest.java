package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerResponse;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkToolSupport;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.engine.PendingUiHints;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext;
import com.yayatechandinnovations.yayaagentic.testsupport.JsonWorkingMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** banca_context_set resolution (design §4/§4.5). */
class BancaContextSetToolTest {

    private final JsonWorkingMemory wm = new JsonWorkingMemory(new ObjectMapper());
    private final Ids.SessionId session = new Ids.SessionId("s1");
    private final HubBrokerClient hub = mock(HubBrokerClient.class);
    private final NetworkRateLimiter rateLimiter = mock(NetworkRateLimiter.class);
    private BancaContextSetTool tool;

    @BeforeEach
    void setUp() {
        NetworkToolSupport support = new NetworkToolSupport(wm);
        tool = new BancaContextSetTool(hub, support, wm, new PendingUiHints(wm), rateLimiter);
        when(rateLimiter.allowPicker(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    private ExecutionContext ctx() {
        Principal p = new Principal("sub", new Ids.TenantId("t"),
                Set.of(), Map.of("phone", "+18091234567"), Instant.now());
        return new ExecutionContext(p, session, new Ids.TurnId("turn1"), "trace", Map.of());
    }

    private void stubBancas(List<Map<String, Object>> linked, List<Map<String, Object>> online) {
        when(hub.linkedBancas(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(BrokerResponse.ok(Map.of("bancas", linked)));
        when(hub.onlineBancas(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(BrokerResponse.ok(Map.of("bancas", online)));
    }

    @Test
    void ambiguousEmitsPickerAndRecordsOfferedCodes() {
        stubBancas(
                List.of(Map.of("code", "LAMINA01", "name", "Banca La Mina", "linked", "ACTIVE")),
                List.of(Map.of("code", "SOL02", "name", "Banca El Sol")));
        Map<String, Object> out = tool.execute(Map.of(), ctx());
        assertEquals("NEEDS_SELECTION", out.get("status"));
        // picker enqueued for the engine to stream
        Object queued = wm.get(session).get("pendingUiHints");
        assertNotNull(queued);
        assertTrue(queued.toString().contains("banca_picker"));
        // offered codes remembered for selection integrity
        assertTrue(BancaContext.wasOffered(wm, session, "LAMINA01"));
        assertTrue(BancaContext.wasOffered(wm, session, "SOL02"));
    }

    @Test
    void resolvedActiveVinculoCommitsActiveContext() {
        stubBancas(
                List.of(Map.of("code", "LAMINA01", "name", "Banca La Mina", "orgId", "org-9", "linked", "ACTIVE")),
                List.of());
        Map<String, Object> out = tool.execute(Map.of("code", "LAMINA01"), ctx());
        assertEquals("OK", out.get("status"));
        assertEquals("ACTIVE", out.get("linked"));
        BancaContext ctx = BancaContext.read(wm, session).orElseThrow();
        assertEquals("LAMINA01", ctx.installCode());
        assertTrue(ctx.isActive());
    }

    @Test
    void resolvedButNotLinkedReturnsConfirmRequest() {
        stubBancas(
                List.of(),
                List.of(Map.of("code", "SOL02", "name", "Banca El Sol")));
        Map<String, Object> out = tool.execute(Map.of("code", "SOL02"), ctx());
        assertEquals("NEEDS_LINK", out.get("status"));
        Object cr = out.get("_confirmRequest");
        assertNotNull(cr);
        assertTrue(cr instanceof Map);
        assertEquals("banca_link_confirm", ((Map<?, ?>) cr).get("toolId"));
        // context set to PENDING, not ACTIVE
        BancaContext ctx = BancaContext.read(wm, session).orElseThrow();
        assertEquals(BancaContext.LinkState.PENDING, ctx.linked());
    }

    @Test
    void unknownCodeReOffersPickerInsteadOfCommitting() {
        stubBancas(
                List.of(Map.of("code", "LAMINA01", "name", "Banca La Mina", "linked", "ACTIVE")),
                List.of());
        Map<String, Object> out = tool.execute(Map.of("code", "GHOST99"), ctx());
        assertEquals("NEEDS_SELECTION", out.get("status"));
        assertTrue(BancaContext.read(wm, session).isEmpty());
    }
}
