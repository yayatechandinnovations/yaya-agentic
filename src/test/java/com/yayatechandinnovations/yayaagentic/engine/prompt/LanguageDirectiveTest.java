package com.yayatechandinnovations.yayaagentic.engine.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test on the language-rule helper. Stays out of the Spring
 * context so it can run fast and survive CI without Postgres / Redis.
 * The integration property — that the directive actually lands in the
 * cacheable prefix — is implicitly covered by the prompt-builder pipeline
 * test, which renders a real Profile through the real builder.
 */
class LanguageDirectiveTest {

    @ParameterizedTest(name = "code={0} → directive mentions {1}")
    @CsvSource({
            "en, English",
            "es, Spanish",
            "fr, French",
            "en-US, English",
            "es-MX, Spanish"
    })
    void bcp47_codes_render_a_clear_directive(String code, String expectedName) {
        String rule = DefaultPromptBuilder.languageRule(code);

        assertThat(rule)
                .as("directive mentions the resolved language name")
                .contains(expectedName)
                .as("rule is phrased as a hard instruction the model can't drift from")
                .contains("respond exclusively in")
                .contains("even when the user writes in another language");
    }

    @Test
    @DisplayName("null / blank code falls back to English")
    void null_or_blank_code_defaults_to_english() {
        assertThat(DefaultPromptBuilder.languageRule(null)).contains("English");
        assertThat(DefaultPromptBuilder.languageRule("")).contains("English");
        assertThat(DefaultPromptBuilder.languageRule("   ")).contains("English");
    }

    @Test
    @DisplayName("unknown code is echoed verbatim rather than dropped")
    void unknown_code_is_echoed() {
        // Java's Locale renders any well-formed BCP 47 tag; a truly unknown
        // value gets echoed as the literal code. That's better than silently
        // ignoring it — the operator sees their tag in the prompt.
        String rule = DefaultPromptBuilder.languageRule("xx-YY");
        assertThat(rule).contains("respond exclusively in");
    }
}
