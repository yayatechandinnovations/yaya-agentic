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
}
