package com.yayatechandinnovations.yayaagentic.api.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The message POST body accepts an optional structured selection (design §3.2)
 *  and stays backward compatible with clients that send only {@code text}. */
class MessageRequestSelectionTest {

    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void parsesBancaSelection() throws Exception {
        PublicChatDtos.MessageRequest req = json.readValue(
                "{\"text\":\"Banca La Mina\",\"selection\":{\"kind\":\"banca\",\"code\":\"LAMINA01\"}}",
                PublicChatDtos.MessageRequest.class);
        assertEquals("Banca La Mina", req.text());
        assertEquals("banca", req.selection().kind());
        assertEquals("LAMINA01", req.selection().code());
    }

    @Test
    void parsesConfirmSelection() throws Exception {
        PublicChatDtos.MessageRequest req = json.readValue(
                "{\"text\":\"Sí\",\"selection\":{\"kind\":\"confirm\",\"callId\":\"c-1\"}}",
                PublicChatDtos.MessageRequest.class);
        assertEquals("confirm", req.selection().kind());
        assertEquals("c-1", req.selection().callId());
    }

    @Test
    void backwardCompatibleWithoutSelection() throws Exception {
        PublicChatDtos.MessageRequest req = json.readValue(
                "{\"text\":\"hola\"}", PublicChatDtos.MessageRequest.class);
        assertEquals("hola", req.text());
        assertNull(req.selection());
    }
}
