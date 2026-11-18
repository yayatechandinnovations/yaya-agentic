package com.yayatechandinnovations.yayaagentic.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Assistant-channels design §2.2: one {@code quick_replies} shape, every producer and the legacy inputs. */
class QuickRepliesTest {

    @Test
    void labels_become_options_with_slug_ids_and_send_equal_to_label() {
        TurnEvent.UiHint hint = QuickReplies.fromLabels(QuickReplies.SOURCE_START, List.of("Ver tickets de hoy", "Cuadre"));
        assertThat(hint.kind()).isEqualTo("quick_replies");
        assertThat(hint.payload().get("source")).isEqualTo("start");
        List<QuickReplies.Option> options = QuickReplies.read(hint.payload());
        assertThat(options).extracting(QuickReplies.Option::id).containsExactly("ver_tickets_de_hoy", "cuadre");
        assertThat(options).extracting(QuickReplies.Option::send).containsExactly("Ver tickets de hoy", "Cuadre");
    }

    @Test
    void rule_chips_keep_label_and_send() {
        TurnEvent.UiHint hint = QuickReplies.fromChips(QuickReplies.SOURCE_RULE,
                List.of(new SuggestionRuleEvaluator.Chip("Anular", "Anular el ticket 4411")));
        List<QuickReplies.Option> options = QuickReplies.read(hint.payload());
        assertThat(options).hasSize(1);
        assertThat(options.get(0).id()).isEqualTo("anular");
        assertThat(options.get(0).label()).isEqualTo("Anular");
        assertThat(options.get(0).send()).isEqualTo("Anular el ticket 4411");
        assertThat(hint.payload().get("source")).isEqualTo("rule");
    }

    @Test
    void legacy_items_and_actions_normalise_to_options() {
        Map<String, Object> items = QuickReplies.normalise(Map.of("items", List.of("A", "B")), "start");
        assertThat(items).containsEntry("source", "start");
        assertThat(QuickReplies.read(items)).extracting(QuickReplies.Option::label).containsExactly("A", "B");
        assertThat(items).doesNotContainKey("items");

        Map<String, Object> actions = QuickReplies.normalise(Map.of(
                "items", List.of("En PDF"),
                "actions", List.of(Map.of("label", "En PDF", "send", "Mándamelo en PDF"))), "rule");
        List<QuickReplies.Option> opts = QuickReplies.read(actions);
        assertThat(opts).hasSize(1);
        assertThat(opts.get(0).send()).isEqualTo("Mándamelo en PDF");
        assertThat(opts.get(0).id()).isEqualTo("en_pdf");
    }

    @Test
    void options_already_canonical_keep_their_ids_source_and_extras() {
        Map<String, Object> payload = Map.of(
                "options", List.of(Map.of("id", "ask_0", "label", "Too late"), Map.of("id", "ask_cancel", "label", "Cancelar", "style", "neutral")),
                "source", "ask", "headerText", "Motivo");
        Map<String, Object> out = QuickReplies.normalise(payload, "rule");
        assertThat(out.get("source")).isEqualTo("ask");
        assertThat(out.get("headerText")).isEqualTo("Motivo");
        List<QuickReplies.Option> opts = QuickReplies.read(out);
        assertThat(opts).extracting(QuickReplies.Option::id).containsExactly("ask_0", "ask_cancel");
        assertThat(opts.get(1).style()).isEqualTo("neutral");
        assertThat(opts.get(0).send()).isEqualTo("Too late");
    }

    @Test
    void duplicate_ids_are_suffixed_and_blank_labels_dropped() {
        TurnEvent.UiHint hint = QuickReplies.hint("rule", List.of(
                QuickReplies.Option.of("Sí"), QuickReplies.Option.of("Si"), QuickReplies.Option.of("  ")));
        List<QuickReplies.Option> opts = QuickReplies.read(hint.payload());
        assertThat(opts).extracting(QuickReplies.Option::id).containsExactly("si", "si_2");
    }

    @Test
    void yes_no_is_localised() {
        assertThat(QuickReplies.yesNo("es")).extracting(QuickReplies.Option::label).containsExactly("Sí", "No");
        assertThat(QuickReplies.yesNo("es")).extracting(QuickReplies.Option::id).containsExactly("yes", "no");
        assertThat(QuickReplies.yesNo("en")).extracting(QuickReplies.Option::label).containsExactly("Yes", "No");
    }

    @Test
    void slug_strips_accents_and_caps_length() {
        assertThat(QuickReplies.slug("¿Más opciones?")).isEqualTo("mas_opciones");
        assertThat(QuickReplies.slug("***")).isEqualTo("opt");
        assertThat(QuickReplies.slug("a".repeat(80))).hasSize(40);
    }

    @Test
    void the_end_helper_emits_the_canonical_shape() {
        TurnEvent.UiHint hint = TurnEvent.End.quickReplies(List.of("Hola"));
        assertThat(hint.payload()).containsKey("options").containsEntry("source", "start");
    }
}
