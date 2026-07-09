package com.yayatechandinnovations.yayaagentic.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.testsupport.JsonWorkingMemory;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingUiHintsTest {

    private static final Ids.SessionId SESSION = new Ids.SessionId("s1");

    private final PendingUiHints hints =
            new PendingUiHints(new JsonWorkingMemory(new ObjectMapper().findAndRegisterModules()));

    @Test
    void drainsInEnqueueOrderThenClears() {
        hints.enqueue(SESSION, "cart", Map.of("itemCount", 2));
        hints.enqueue(SESSION, "product_catalog", Map.of("total", 9));

        List<TurnEvent.UiHint> drained = hints.drain(SESSION);

        assertEquals(2, drained.size());
        assertEquals("cart", drained.get(0).kind());
        assertEquals(2, drained.get(0).payload().get("itemCount"));
        assertEquals("product_catalog", drained.get(1).kind());

        // Draining is destructive — the queue is empty afterwards.
        assertTrue(hints.drain(SESSION).isEmpty());
    }

    @Test
    void drainWithNothingQueuedIsEmpty() {
        assertTrue(hints.drain(SESSION).isEmpty());
    }
}
