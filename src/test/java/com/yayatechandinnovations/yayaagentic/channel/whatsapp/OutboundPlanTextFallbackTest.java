package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.InteractivePayload;
import com.yayatechandinnovations.yayaagentic.channel.InteractiveRenderer;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.engine.confirm.PlanAnswers;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plan that cannot go out with its buttons (longer than a button message
 * may be, or the buttons failed) is sent as text. Its steps are already a
 * numbered list, so the two answers are words, never «1. … 2. …»
 * (admin-panel-sessions.md §16).
 */
class OutboundPlanTextFallbackTest {

    private static final String TEXT = "Voy a hacer esto, en orden:\n1. Detener la venta en la estación 12\n"
            + "2. Enviar el aviso «Cierre» a la estación 07\n¿Lo hago?";

    private final DefaultInteractiveRenderer renderer = new DefaultInteractiveRenderer();
    private final InteractiveRenderer.RenderContext ctx =
            new InteractiveRenderer.RenderContext(new ChannelKind.WhatsApp(), null);

    private static TurnEvent.UiHint plan(String confirmLabel, String cancelLabel) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("callId", "plan-1");
        plan.put("steps", List.of(Map.of("id", "1"), Map.of("id", "2")));
        plan.put("confirmLabel", confirmLabel);
        plan.put("cancelLabel", cancelLabel);
        plan.put("text", TEXT);
        return new TurnEvent.UiHint("plan", plan);
    }

    @Test
    void a_plan_sent_as_text_asks_for_words_and_has_no_second_numbered_list() {
        TurnEvent.UiHint hint = plan("Hacer todo", "Cancelar");
        String sent = OutboundDispatcher.textFallback(renderer.render(TEXT, hint, ctx), hint);

        assertThat(sent).isEqualTo(TEXT + "\n\nResponde TODO para hacerlos todos, CANCELAR para no hacer nada, "
                + "o dime cuáles pasos quieres (por ejemplo «solo la 1»).");
        assertThat(sent).doesNotContain("1. Hacer todo").doesNotContain("2. Cancelar");
        // The words it asks for are the ones a typed reply is read by.
        List<String> labels = List.of("Hacer todo", "Cancelar");
        assertThat(PlanAnswers.read("TODO", labels, 2, false).kind()).isEqualTo(PlanAnswers.Kind.ALL);
        assertThat(PlanAnswers.read("CANCELAR", labels, 2, false).kind()).isEqualTo(PlanAnswers.Kind.NONE);
        assertThat(PlanAnswers.read("solo la 1", labels, 2, false).stepIds()).containsExactly("1");
    }

    @Test
    void an_english_plan_asks_in_english_and_the_instruction_is_not_added_twice() {
        TurnEvent.UiHint hint = plan("Do all", "Cancel");
        String once = OutboundDispatcher.textFallback(renderer.render("I will do this, in order:\n1. A\n2. B\nShall I?",
                hint, ctx), hint);
        assertThat(once).endsWith("\n\nReply ALL to do them all, CANCEL to do nothing, "
                + "or tell me which steps you want (for example \"only 1\").");
        List<String> labels = List.of("Do all", "Cancel");
        assertThat(PlanAnswers.read("ALL", labels, 2, false).kind()).isEqualTo(PlanAnswers.Kind.ALL);
        assertThat(PlanAnswers.read("CANCEL", labels, 2, false).kind()).isEqualTo(PlanAnswers.Kind.NONE);
        assertThat(PlanAnswers.read("only 1", labels, 2, false).stepIds()).containsExactly("1");

        // The question that follows an unclear answer already ends with it.
        String asked = "No quiero hacer de más. Responde TODO para hacerlos todos, CANCELAR para no hacer nada, "
                + "o dime cuáles pasos quieres (por ejemplo «solo la 1»).";
        TurnEvent.UiHint es = plan("Hacer todo", "Cancelar");
        assertThat(OutboundDispatcher.textFallback(renderer.render(asked, es, ctx), es)).isEqualTo(asked);
    }

    @Test
    void a_single_confirmation_keeps_its_numbered_options() {
        TurnEvent.UiHint confirm = new TurnEvent.UiHint("confirm", Map.of("callId", "c-1",
                "confirmLabel", "Confirmar", "cancelLabel", "Cancelar", "summary", "¿Aplicar «Especial 80»?"));
        InteractivePayload payload = renderer.render("¿Aplicar «Especial 80»?", confirm, ctx);
        assertThat(OutboundDispatcher.textFallback(payload, confirm))
                .isEqualTo("¿Aplicar «Especial 80»?\n\n1. Confirmar\n2. Cancelar");
    }
}
