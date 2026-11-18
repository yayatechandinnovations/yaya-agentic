package com.yayatechandinnovations.yayaagentic.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Assistant-channels design §2.2: a selection carries a bound action's id and a tapped option. */
class SelectionDtosTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void a_sessions_message_selection_keeps_action_id_and_option_fields() throws Exception {
        SessionDtos.MessageRequest req = json.readValue(
                "{\"text\":\"Anular\",\"selection\":{\"kind\":\"option\",\"id\":\"undo\",\"send\":\"Anular el ticket 4411\"}}",
                SessionDtos.MessageRequest.class);
        assertThat(req.selection().kind()).isEqualTo("option");
        assertThat(req.selection().id()).isEqualTo("undo");
        assertThat(req.selection().send()).isEqualTo("Anular el ticket 4411");

        SessionDtos.MessageRequest tap = json.readValue(
                "{\"text\":\"Aprobar\",\"selection\":{\"kind\":\"action\",\"actionId\":\"a4\"}}",
                SessionDtos.MessageRequest.class);
        assertThat(tap.selection().actionId()).isEqualTo("a4");
        assertThat(tap.selection().toString()).doesNotContain("a4");
        // The older shapes still bind.
        SessionDtos.MessageRequest confirm = json.readValue(
                "{\"text\":\"\",\"selection\":{\"kind\":\"confirm\",\"callId\":\"c1\",\"itemIds\":[\"x\"]}}",
                SessionDtos.MessageRequest.class);
        assertThat(confirm.selection().callId()).isEqualTo("c1");
        assertThat(confirm.selection().actionId()).isNull();
    }

    @Test
    void a_public_chat_selection_keeps_the_same_fields() throws Exception {
        PublicChatDtos.MessageRequest req = json.readValue(
                "{\"text\":\"Sí\",\"selection\":{\"kind\":\"option\",\"id\":\"yes\",\"send\":\"Sí\",\"actionId\":null}}",
                PublicChatDtos.MessageRequest.class);
        assertThat(req.selection().kind()).isEqualTo("option");
        assertThat(req.selection().id()).isEqualTo("yes");
        assertThat(req.selection().send()).isEqualTo("Sí");
        PublicChatDtos.MessageRequest tap = json.readValue(
                "{\"text\":\"Aprobar\",\"selection\":{\"kind\":\"action\",\"actionId\":\"a9\"}}",
                PublicChatDtos.MessageRequest.class);
        assertThat(tap.selection().actionId()).isEqualTo("a9");
        assertThat(new PublicChatDtos.MessageRequest.Selection("banca", "B1", null).code()).isEqualTo("B1");
    }
}
