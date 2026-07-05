package com.yayatechandinnovations.yayaagentic.channel.websse;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Contract guard for the commerce-rendering fix (Pillar B §B.1): a
 * {@code UiHint} turn event must map to the SSE {@code event: ui_hint} frame —
 * never fold into {@code token} (assistant prose) — so a client renders the
 * catalog as a card, not raw JSON. The public chat and the playground share this
 * exact mapping, so locking it here guards both against a regression that would
 * bring back the JSON-dump. See
 * {@code commerce-redesign-authoring-rendering-paypal.md} Pillar B.
 */
class WebSseChannelAdapterTest {

    private final WebSseChannelAdapter adapter = new WebSseChannelAdapter();

    @Test
    void uiHintMapsToUiHintEventNotToken() {
        ServerSentEvent<Object> sse = adapter.toSse(
                new TurnEvent.UiHint("product_catalog", Map.of("items", java.util.List.of())));
        assertEquals("ui_hint", sse.event());
    }

    @Test
    void eachTurnEventKeepsItsWireName() {
        assertEquals("token", adapter.toSse(new TurnEvent.Token("hi")).event());
        assertEquals("ui_hint", adapter.toSse(new TurnEvent.UiHint("cart", Map.of())).event());
        assertEquals("end",
                adapter.toSse(new TurnEvent.End(new Ids.TurnId("t1"), 0, 0)).event());
    }

    @Test
    void theEventPayloadIsTheTurnEventItself() {
        TurnEvent.UiHint hint = new TurnEvent.UiHint("cart", Map.of("lines", java.util.List.of()));
        ServerSentEvent<Object> sse = adapter.toSse(hint);
        assertSame(hint, sse.data());   // client receives {kind, payload}, never a stringified blob
    }
}
