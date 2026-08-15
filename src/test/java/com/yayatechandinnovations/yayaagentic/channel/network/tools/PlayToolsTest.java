package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerResponse;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkToolSupport;
import com.yayatechandinnovations.yayaagentic.channel.network.PlayCredential;
import com.yayatechandinnovations.yayaagentic.channel.network.PlayCredentialCache;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext.LinkState;
import com.yayatechandinnovations.yayaagentic.testsupport.JsonWorkingMemory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Play-path redaction + preconditions (design §4/§6). */
class PlayToolsTest {

    private final JsonWorkingMemory wm = new JsonWorkingMemory(new ObjectMapper());
    private final Ids.SessionId session = new Ids.SessionId("s1");
    private final HubBrokerClient hub = mock(HubBrokerClient.class);
    private final NetworkToolSupport support = new NetworkToolSupport(wm);
    private final PlayCredentialCache credentials = new PlayCredentialCache();
    private final NetworkRateLimiter rateLimiter = mock(NetworkRateLimiter.class);

    private ExecutionContext ctx() {
        Principal p = new Principal("sub", new Ids.TenantId("t"),
                Set.of(), Map.of("phone", "+18091234567"), Instant.now());
        return new ExecutionContext(p, session, new Ids.TurnId("turn1"), "trace", Map.of());
    }

    private void commitActiveBanca() {
        BancaContext.commit(wm, session,
                new BancaContext("LAMINA01", "org-9", "Banca La Mina", LinkState.ACTIVE, Instant.now()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void playQuoteNeverLeaksCustomerTokenAndBindsPendingQuote() {
        commitActiveBanca();
        when(hub.playSession(ArgumentMatchers.eq("play"), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(BrokerResponse.ok(Map.of("playCredential", Map.of())));
        when(hub.parseCredential(ArgumentMatchers.any()))
                .thenReturn(Optional.of(new PlayCredential(
                        "https://install.example", "cust-1", "SECRET-TOKEN", Instant.now().plusSeconds(600))));
        // The tool retries once on TOKEN_EXPIRED, so allow any invocation to
        // return the quote directly. Include a leaked token to prove redaction.
        when(hub.playSurface(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("/player/play/quote"), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(BrokerResponse.ok(Map.of(
                        "quoteId", "Q1", "draw", "hoy 8pm", "customerToken", "SECRET-TOKEN")));

        PlayQuoteTool tool = new PlayQuoteTool(hub, support, wm, credentials, new ObjectMapper());
        Map<String, Object> out = tool.execute(Map.of("numbers", "12-34"), ctx());

        assertEquals("OK", out.get("status"));
        Map<String, Object> quote = (Map<String, Object>) out.get("quote");
        assertFalse(quote.containsKey("customerToken"), "customerToken must be stripped");
        assertEquals("Q1", quote.get("quoteId"));
        // pending_quote bound for the engine's approved-quote gate
        Object pq = wm.get(session).get(BancaContext.PENDING_QUOTE_SLOT);
        assertTrue(pq instanceof Map);
        assertEquals("Q1", ((Map<?, ?>) pq).get("quoteId"));
    }

    @Test
    void playConfirmWithoutPendingQuoteIsRefused() {
        commitActiveBanca();
        when(rateLimiter.allowPlayConfirm(ArgumentMatchers.anyString())).thenReturn(true);
        PlayConfirmTool tool = new PlayConfirmTool(hub, support, wm, credentials, rateLimiter);
        Map<String, Object> out = tool.execute(Map.of("quoteId", "Q1"), ctx());
        assertEquals("ERROR", out.get("status"));
    }

    @Test
    void playConfirmRateLimitedIsRefused() {
        commitActiveBanca();
        when(rateLimiter.allowPlayConfirm(ArgumentMatchers.anyString())).thenReturn(false);
        PlayConfirmTool tool = new PlayConfirmTool(hub, support, wm, credentials, rateLimiter);
        Map<String, Object> out = tool.execute(Map.of("quoteId", "Q1"), ctx());
        assertEquals("ERROR", out.get("status"));
        assertEquals("RATE_LIMITED", out.get("error"));
    }
}
