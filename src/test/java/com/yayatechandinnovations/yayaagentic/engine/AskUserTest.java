package com.yayatechandinnovations.yayaagentic.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Assistant-channels design §2.4: the reserved {@code _askUser} result key. */
class AskUserTest {

    @Test
    void the_ask_is_stripped_and_its_options_read() {
        Map<String, Object> value = Map.of("ok", true, "_askUser", Map.of("kind", "choice",
                "question", "¿Para todas?",
                "options", List.of(Map.of("id", "all", "label", "Todas", "send", "Para todas las estaciones"),
                        Map.of("id", "one", "label", "Solo la 07"))));
        AskUser.Split split = AskUser.split(value);
        assertThat(split).isNotNull();
        assertThat(split.strippedValue()).isEqualTo(Map.of("ok", true));
        List<QuickReplies.Option> options = AskUser.options(split.ask(), "es");
        assertThat(options).extracting(QuickReplies.Option::id).containsExactly("all", "one");
        assertThat(options.get(0).send()).isEqualTo("Para todas las estaciones");
        assertThat(options.get(1).send()).isEqualTo("Solo la 07");
    }

    @Test
    void yes_no_without_options_is_the_localised_pair() {
        AskUser.Split split = AskUser.split(Map.of("_askUser", Map.of("kind", "yes_no", "question", "¿Lo mando?")));
        assertThat(AskUser.options(split.ask(), "es")).extracting(QuickReplies.Option::label).containsExactly("Sí", "No");
        assertThat(AskUser.options(split.ask(), "en")).extracting(QuickReplies.Option::label).containsExactly("Yes", "No");
    }

    @Test
    void a_malformed_ask_is_still_stripped_and_offers_nothing() {
        AskUser.Split split = AskUser.split(Map.of("ok", true, "_askUser", "yes"));
        assertThat(split).isNotNull();
        assertThat(split.ask()).isNull();
        assertThat(split.strippedValue()).isEqualTo(Map.of("ok", true));
        assertThat(AskUser.options(null, "es")).isEmpty();
    }

    @Test
    void a_value_without_the_key_is_untouched() {
        assertThat(AskUser.split(Map.of("ok", true))).isNull();
        assertThat(AskUser.split("text")).isNull();
        assertThat(AskUser.split(null)).isNull();
    }
}
