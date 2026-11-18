package com.yayatechandinnovations.yayaagentic.engine.suggest;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.engine.QuickReplies;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient;
import com.yayatechandinnovations.yayaagentic.llm.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Assistant-channels design §2.5: the reply suggester's validation, gating and failure modes. */
class ReplySuggesterTest {

    private static YayaAgenticProperties stubProvider() {
        return new YayaAgenticProperties(null, null, null, null, new YayaAgenticProperties.Llm("stub"),
                null, null, null, null, null, null, null);
    }

    private static ReplySuggester suggester(StubLlmClient stub, SuggestProperties props) {
        return new ReplySuggester(List.of(stub), stubProvider(), props);
    }

    // ---- the question gate ----------------------------------------------

    @Test
    void only_a_text_whose_last_sentence_is_a_question_qualifies() {
        assertThat(ReplySuggester.endsWithQuestion("Listo. ¿Quieres que te lo mande en PDF?")).isTrue();
        assertThat(ReplySuggester.endsWithQuestion("¿Lo detengo en la 12 o en todo el grupo?  ")).isTrue();
        assertThat(ReplySuggester.endsWithQuestion("**¿Seguimos?**")).isTrue();
        assertThat(ReplySuggester.endsWithQuestion("¿Seguimos? Dime.")).isFalse();
        assertThat(ReplySuggester.endsWithQuestion("Hecho.")).isFalse();
        assertThat(ReplySuggester.endsWithQuestion("")).isFalse();
        assertThat(ReplySuggester.endsWithQuestion(null)).isFalse();
    }

    // ---- validation -------------------------------------------------------

    @Test
    void none_gives_nothing_and_yes_no_gives_the_fixed_pair() {
        assertThat(ReplySuggester.validate(Map.of("kind", "none", "options", List.of("x")), "es")).isEmpty();
        assertThat(ReplySuggester.validate(Map.of("kind", "yes_no", "options", List.of()), "es"))
                .extracting(QuickReplies.Option::label).containsExactly("Sí", "No");
        assertThat(ReplySuggester.validate(Map.of("kind", "YES_NO"), "en"))
                .extracting(QuickReplies.Option::label).containsExactly("Yes", "No");
    }

    @Test
    void choice_is_trimmed_deduplicated_and_cut_at_three() {
        List<QuickReplies.Option> options = ReplySuggester.validate(Map.of("kind", "choice",
                "options", List.of(" En la 12 ", "", "en la 12", "En todo el grupo", "Ninguna", "Otra más")), "es");
        assertThat(options).extracting(QuickReplies.Option::label)
                .containsExactly("En la 12", "En todo el grupo", "Ninguna");
        assertThat(options).extracting(QuickReplies.Option::send)
                .containsExactly("En la 12", "En todo el grupo", "Ninguna");
    }

    @Test
    void one_label_over_twenty_characters_rejects_the_whole_suggestion() {
        assertThat(ReplySuggester.validate(Map.of("kind", "choice",
                "options", List.of("Sí", "Una etiqueta demasiado larga para un botón")), "es")).isEmpty();
        assertThat(ReplySuggester.validate(Map.of("kind", "choice",
                "options", List.of("12345678901234567890")), "es")).hasSize(1);
    }

    @Test
    void a_yes_and_no_choice_is_the_fixed_pair_and_garbage_is_nothing() {
        assertThat(ReplySuggester.validate(Map.of("kind", "choice", "options", List.of("Si", "No")), "es"))
                .extracting(QuickReplies.Option::id).containsExactly("yes", "no");
        assertThat(ReplySuggester.validate(Map.of("kind", "choice"), "es")).isEmpty();
        assertThat(ReplySuggester.validate(Map.of("kind", "choice", "options", "Sí"), "es")).isEmpty();
        assertThat(ReplySuggester.validate(Map.of("options", List.of("Sí")), "es")).isEmpty();
        assertThat(ReplySuggester.validate(null, "es")).isEmpty();
    }

    // ---- the call -----------------------------------------------------------

    @Test
    void the_stub_answers_canned_so_the_path_runs_offline() {
        StubLlmClient stub = new StubLlmClient();
        Optional<ReplySuggester.Suggestion> s = suggester(stub, SuggestProperties.defaults())
                .suggestNow("hola", "¿Quieres que lo haga?", "es");
        assertThat(s).isPresent();
        assertThat(s.get().options()).extracting(QuickReplies.Option::label).containsExactly("Sí, hazlo", "Mejor no");
        assertThat(s.get().usage().inputTokens()).isPositive();
    }

    @Test
    void the_input_is_the_last_user_text_the_assistant_tail_and_the_language_only() {
        StubLlmClient stub = new StubLlmClient();
        AtomicReference<StructuredLlmClient.StructuredRequest> seen = new AtomicReference<>();
        stub.setStructuredResponder(r -> {
            seen.set(r);
            return Map.of("kind", "yes_no", "options", List.of());
        });
        String longAnswer = "x".repeat(2000) + " ¿Lo mando?";
        suggester(stub, new SuggestProperties(true, "claude-haiku-4-5", null))
                .suggestNow("mándamelo", longAnswer, "es");
        assertThat(seen.get().toolName()).isEqualTo(ReplySuggester.TOOL_NAME);
        assertThat(seen.get().model()).isEqualTo("claude-haiku-4-5");
        assertThat(seen.get().input()).startsWith("Language: es").contains("User: mándamelo").endsWith("¿Lo mando?");
        assertThat(seen.get().input().length()).isLessThan(1400);
        assertThat(seen.get().system()).doesNotContain("tool_result");
    }

    @Test
    void a_timeout_gives_no_chips_and_never_retries() {
        StubLlmClient stub = new StubLlmClient();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        stub.setStructuredResponder(r -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("kind", "yes_no", "options", List.of());
        });
        ReplySuggester s = suggester(stub, new SuggestProperties(true, null, Duration.ofMillis(50)));
        assertThat(s.suggest("a", "¿Sí?", "es").blockOptional()).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void a_provider_failure_or_an_invalid_answer_gives_no_chips() {
        StubLlmClient stub = new StubLlmClient();
        stub.setStructuredResponder(r -> { throw new StructuredLlmClient.StructuredLlmException("boom", null); });
        ReplySuggester s = suggester(stub, SuggestProperties.defaults());
        assertThat(s.suggest("a", "¿Sí?", "es").blockOptional()).isEmpty();

        stub.setStructuredResponder(r -> Map.of("kind", "choice", "options", List.of("una opción demasiado larga de verdad")));
        assertThat(s.suggest("a", "¿Sí?", "es").blockOptional()).isEmpty();
    }

    @Test
    void disabled_by_configuration_means_no_call() {
        StubLlmClient stub = new StubLlmClient();
        stub.setStructuredResponder(r -> { throw new AssertionError("must not be called"); });
        ReplySuggester s = suggester(stub, new SuggestProperties(false, null, null));
        assertThat(s.enabled()).isFalse();
        assertThat(s.suggest("a", "¿Sí?", "es").blockOptional()).isEmpty();
        assertThat(new ReplySuggester(List.of(), stubProvider(), SuggestProperties.defaults()).enabled()).isFalse();
    }

    @Test
    void properties_default_to_enabled_with_a_second_and_a_half() {
        SuggestProperties p = SuggestProperties.defaults();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.effectiveTimeout()).isEqualTo(Duration.ofMillis(1500));
        assertThat(p.modelOrNull()).isNull();
        assertThat(new SuggestProperties(null, " ", Duration.ZERO).effectiveTimeout()).isEqualTo(Duration.ofMillis(1500));
    }
}
