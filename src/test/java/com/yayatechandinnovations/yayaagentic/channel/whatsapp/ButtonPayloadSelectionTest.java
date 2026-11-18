package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.InteractivePayload;
import com.yayatechandinnovations.yayaagentic.channel.InteractiveRenderer;
import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** §6.5 of whatsapp-ops-integration-design.md: button payloads round-trip. */
class ButtonPayloadSelectionTest {

    @Test
    void payloads_map_to_engine_selections() {
        assertThat(InboundDispatchWorker.selectionFor("act:abc", "button"))
                .isEqualTo(Map.of("kind", "action", "actionId", "abc"));
        assertThat(InboundDispatchWorker.selectionFor("confirm:c1", "button"))
                .isEqualTo(Map.of("kind", "confirm", "callId", "c1"));
        assertThat(InboundDispatchWorker.selectionFor("cancel:c1", "button"))
                .isEqualTo(Map.of("kind", "cancel", "callId", "c1"));
        assertThat(InboundDispatchWorker.selectionFor("act:none", "button")).isNull();
        assertThat(InboundDispatchWorker.selectionFor("qr_0", "button")).isNull();
        assertThat(InboundDispatchWorker.selectionFor("act:abc", "list_row")).isNull();
    }

    @Test
    void the_confirm_frame_renders_as_two_buttons_naming_the_frame() {
        var payload = new DefaultInteractiveRenderer().render("¿Aprobar UR-1?",
                new TurnEvent.UiHint("confirm", Map.of("callId", "c1", "summary", "¿Aprobar UR-1?",
                        "confirmLabel", "Confirmar", "cancelLabel", "Cancelar")),
                new InteractiveRenderer.RenderContext(new ChannelKind.WhatsApp(), null));

        assertThat(payload).isInstanceOf(InteractivePayload.Buttons.class);
        var buttons = (InteractivePayload.Buttons) payload;
        assertThat(buttons.body()).isEqualTo("¿Aprobar UR-1?");
        assertThat(buttons.buttons()).extracting(InteractivePayload.Button::id)
                .containsExactly("confirm:c1", "cancel:c1");
        assertThat(buttons.buttons()).extracting(InteractivePayload.Button::title)
                .containsExactly("Confirmar", "Cancelar");
    }

    // ---- assistant-channels design §2.6: options map back to what was offered ----

    private static OfferedOptionsStore.Offered offered(int n, boolean numbered) {
        var options = new java.util.ArrayList<com.yayatechandinnovations.yayaagentic.engine.QuickReplies.Option>();
        for (int i = 1; i <= n; i++) {
            options.add(new com.yayatechandinnovations.yayaagentic.engine.QuickReplies.Option(
                    "o" + i, "Opción " + i, "Elijo la " + i));
        }
        return new OfferedOptionsStore.Offered("sess", options, numbered, 0,
                com.yayatechandinnovations.yayaagentic.engine.QuickReplies.payload("rule", options));
    }

    @Test
    void a_tapped_option_or_list_row_becomes_an_option_selection_with_its_send() {
        var button = new InboundDispatchWorker.InteractiveSelection("opt:o2", "Opción 2", "button");
        var reply = InboundDispatchWorker.optionReplyFor(button, "Opción 2", offered(3, false));
        assertThat(reply.send()).isEqualTo("Elijo la 2");
        assertThat(reply.selection()).isEqualTo(Map.of("kind", "option", "id", "o2", "send", "Elijo la 2"));
        assertThat(reply.more()).isFalse();

        var row = new InboundDispatchWorker.InteractiveSelection("opt:o3", "Opción 3", "list_row");
        assertThat(InboundDispatchWorker.optionReplyFor(row, "Opción 3", offered(3, false)).send()).isEqualTo("Elijo la 3");
    }

    @Test
    void a_tap_after_the_offered_set_expired_still_sends_its_label() {
        var button = new InboundDispatchWorker.InteractiveSelection("opt:undo", "Anular", "button");
        var reply = InboundDispatchWorker.optionReplyFor(button, "Anular", null);
        assertThat(reply.send()).isEqualTo("Anular");
        assertThat(reply.selection()).isEqualTo(Map.of("kind", "option", "id", "undo", "send", "Anular"));
        // A frame or action button is not an option.
        assertThat(InboundDispatchWorker.optionReplyFor(
                new InboundDispatchWorker.InteractiveSelection("confirm:c1", "Confirmar", "button"), "Confirmar", offered(2, false))).isNull();
        assertThat(InboundDispatchWorker.optionReplyFor(
                new InboundDispatchWorker.InteractiveSelection("act:a1", "Aprobar", "button"), "Aprobar", null)).isNull();
        // An untagged button payload is plain text, as before.
        assertThat(InboundDispatchWorker.optionReplyFor(
                new InboundDispatchWorker.InteractiveSelection("Hola", "Hola", "button"), "Hola", null)).isNull();
    }

    @Test
    void a_bare_number_maps_within_the_window_after_a_numbered_list_and_not_otherwise() {
        var reply = InboundDispatchWorker.optionReplyFor(null, "2", offered(4, true));
        assertThat(reply.send()).isEqualTo("Elijo la 2");
        assertThat(reply.selection()).isEqualTo(Map.of("kind", "option", "id", "o2", "send", "Elijo la 2"));
        // The set expired (20 min): a digit is a digit.
        assertThat(InboundDispatchWorker.optionReplyFor(null, "2", null)).isNull();
        // Native buttons went out: a digit is a digit.
        assertThat(InboundDispatchWorker.optionReplyFor(null, "2", offered(4, false))).isNull();
        assertThat(InboundDispatchWorker.optionReplyFor(null, "2 tickets", offered(4, true))).isNull();
    }

    @Test
    void the_more_row_asks_for_the_next_page_instead_of_the_engine() {
        var tapped = InboundDispatchWorker.optionReplyFor(
                new InboundDispatchWorker.InteractiveSelection("opt:__more__", "Más…", "list_row"), "Más…", offered(12, false));
        assertThat(tapped.more()).isTrue();
        assertThat(tapped.selection()).isNull();
        var typed = InboundDispatchWorker.optionReplyFor(null, "10", offered(12, true));
        assertThat(typed.more()).isTrue();
        // Nothing to page without the offered set.
        assertThat(InboundDispatchWorker.optionReplyFor(
                new InboundDispatchWorker.InteractiveSelection("opt:__more__", "Más…", "list_row"), "Más…", null)).isNull();
    }
}
