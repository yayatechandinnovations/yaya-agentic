package com.yayatechandinnovations.yayaagentic.engine.secret;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.SecretInboundRedaction;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecretPromptsTest {

    private static Map<String, Object> pending(String format, Integer length) {
        Map<String, Object> prompt = new java.util.LinkedHashMap<>();
        prompt.put("format", format);
        if (length != null) prompt.put("length", length);
        Map<String, Object> frame = SecretPrompts.frame("c1", prompt, "es");
        return SecretPrompts.pending("tool", Map.of("a", 1), frame, "t1", Instant.now());
    }

    private static String code(Map<String, Object> pending, String text) {
        SecretPrompts.Typed typed = SecretPrompts.readTyped(pending, text);
        return typed.kind() == SecretPrompts.TypedKind.CODE ? typed.value() : null;
    }

    private static SecretPrompts.TypedKind kind(Map<String, Object> pending, String text) {
        return SecretPrompts.readTyped(pending, text).kind();
    }

    @Test
    void a_typed_message_is_the_code_when_exactly_one_part_of_it_has_the_prompts_format_and_length() {
        Map<String, Object> digits6 = pending("digits", 6);
        assertThat(code(digits6, "481516")).isEqualTo("481516");
        assertThat(code(digits6, " 481 516 ")).isEqualTo("481516");
        assertThat(code(digits6, "481-516")).isEqualTo("481516");
        // Review 2R, R3: the code is found inside what people actually write.
        assertThat(code(digits6, "mi código es 481516")).isEqualTo("481516");
        assertThat(code(digits6, "481516.")).isEqualTo("481516");
        assertThat(code(digits6, "es el 481 516, gracias")).isEqualTo("481516");
        assertThat(code(digits6, "481516 lo mandé 2 veces")).isEqualTo("481516");
        assertThat(code(digits6, "481516 481516")).isEqualTo("481516");
        assertThat(code(digits6, "٤٨١٥١٦")).isEqualTo("481516");

        // A number that is not one code of that length is never an ordinary message.
        for (String text : List.of("48151", "4815167", "48151a", "481516 o 902718", "la estación 07")) {
            assertThat(kind(digits6, text)).as(text).isEqualTo(SecretPrompts.TypedKind.SUSPECT);
        }
        // No digit, no code.
        assertThat(kind(digits6, "mejor dime las ventas")).isEqualTo(SecretPrompts.TypedKind.NONE);
        assertThat(kind(digits6, "")).isEqualTo(SecretPrompts.TypedKind.NONE);
        assertThat(kind(digits6, null)).isEqualTo(SecretPrompts.TypedKind.NONE);

        Map<String, Object> alnum5 = pending("alphanumeric", 5);
        assertThat(code(alnum5, "A9-1b2")).isEqualTo("A91b2");
        assertThat(code(alnum5, "el código es A91b2.")).isEqualTo("A91b2");
        // Any word could be such a code: what is not read as it goes nowhere.
        assertThat(kind(alnum5, "A9_b2")).isEqualTo(SecretPrompts.TypedKind.SUSPECT);
        assertThat(kind(alnum5, "no me llega nada")).isEqualTo(SecretPrompts.TypedKind.SUSPECT);

        // An unknown format needs one unbroken word of the stated length.
        assertThat(code(pending("text", 4), "ab#1")).isEqualTo("ab#1");
        assertThat(kind(pending("text", 4), "ab 1x")).isEqualTo(SecretPrompts.TypedKind.SUSPECT);
    }

    @Test
    void r13_a_prompt_that_states_no_length_is_never_answered_by_a_typed_message() {
        assertThat(kind(pending("digits", null), "1234")).isEqualTo(SecretPrompts.TypedKind.SUSPECT);
        assertThat(kind(pending("digits", null), "hola")).isEqualTo(SecretPrompts.TypedKind.NONE);
        assertThat(kind(pending("alphanumeric", null), "cancelar")).isEqualTo(SecretPrompts.TypedKind.SUSPECT);
        assertThat(kind(pending("text", null), "abcd")).isEqualTo(SecretPrompts.TypedKind.SUSPECT);
    }

    @Test
    void a_code_typed_just_after_the_prompt_lapsed_is_still_recognised() {
        Instant now = Instant.now();
        Map<String, Object> frame = SecretPrompts.frame("c1", Map.of("length", 6, "expiresInSeconds", 60), "es");
        Map<String, Object> pending = SecretPrompts.pending("tool", Map.of(), frame, "t1", now);

        assertThat(SecretPrompts.recentlyLapsed(pending, now.plusSeconds(59))).isFalse();
        assertThat(SecretPrompts.recentlyLapsed(pending, now.plusSeconds(60))).isTrue();
        assertThat(SecretPrompts.recentlyLapsed(pending, now.plusSeconds(60 + 299))).isTrue();
        assertThat(SecretPrompts.recentlyLapsed(pending, now.plusSeconds(60 + 300))).isFalse();

        assertThat(SecretPrompts.looksLikeCode(pending, "mi código es 481516")).isTrue();
        // After the lapse only the code itself is held back: other numbers are messages again.
        assertThat(SecretPrompts.looksLikeCode(pending, "la estación 07")).isFalse();
        assertThat(SecretPrompts.looksLikeCode(pending("digits", null), "es 48151623")).isTrue();
        assertThat(SecretPrompts.looksLikeCode(pending("digits", null), "la 07")).isFalse();
    }

    @Test
    void the_resend_count_travels_with_the_prompt() {
        Map<String, Object> frame = SecretPrompts.frame("c1", Map.of(), "es");
        assertThat(SecretPrompts.resends(SecretPrompts.pending("tool", Map.of(), frame, "t1", Instant.now())))
                .isZero();
        assertThat(SecretPrompts.resends(SecretPrompts.pending("tool", Map.of(), frame, "t1", Instant.now(), 2)))
                .isEqualTo(2);
        assertThat(SecretPrompts.resends(null)).isZero();
    }

    @Test
    void the_frame_has_the_contract_fields_and_the_held_call_lapses() {
        Map<String, Object> frame = SecretPrompts.frame("c1", Map.of("title", "T", "message", "M",
                "format", "digits", "length", 6, "expiresInSeconds", 300, "attemptsLeft", 3, "canResend", true), "es");
        assertThat(frame).containsOnlyKeys("callId", "title", "message", "format", "length",
                "expiresInSeconds", "attemptsLeft", "canResend");

        Instant now = Instant.now();
        Map<String, Object> pending = SecretPrompts.pending("tool", Map.of("a", 1), frame, "t1", now);
        assertThat(pending).containsKeys("toolId", "args", "callId", "format", "length", "expiresAt");
        assertThat(SecretPrompts.lapsed(pending, now.plusSeconds(299))).isFalse();
        assertThat(SecretPrompts.lapsed(pending, now.plusSeconds(300))).isTrue();
        assertThat(SecretPrompts.view(pending, now.plusSeconds(100)))
                .containsEntry("expiresInSeconds", 200L).containsKey("expiresAt").doesNotContainKey("args");
        assertThat(SecretPrompts.view(pending, now.plusSeconds(301))).isNull();

        // Defaults when the tool leaves fields out.
        assertThat(SecretPrompts.frame("c2", Map.of(), "es"))
                .containsEntry("format", "digits").containsEntry("expiresInSeconds", 300)
                .containsEntry("canResend", false).doesNotContainKeys("length", "attemptsLeft");
    }

    @Test
    void a_submitted_value_must_be_short_and_printable() {
        assertThat(SecretPrompts.acceptable("481516")).isTrue();
        assertThat(SecretPrompts.acceptable("")).isTrue();
        assertThat(SecretPrompts.acceptable("12\r\nX-Other: 1")).isFalse();
        assertThat(SecretPrompts.acceptable("9".repeat(65))).isFalse();
    }

    @Test
    void an_inbound_row_that_carried_a_code_keeps_no_copy_of_it() {
        InboundMessageEntity row = new InboundMessageEntity("SM1", "t", "sender", "+18095550000", 0,
                "{\"MessageSid\":\"SM1\",\"Body\":\"481 516\",\"From\":\"whatsapp:+18095550000\"}");
        row.setBodyRedacted("481 516");
        row.setMediaTranscriptsJson("[{\"text\":\"481516\"}]");

        SecretInboundRedaction.apply(row, new ObjectMapper());

        assertThat(row.getBodyRedacted()).isEqualTo("(código ingresado)");
        assertThat(row.getRawPayloadJson()).doesNotContain("481").contains("\"MessageSid\":\"SM1\"")
                .contains("(código ingresado)");
        assertThat(row.getMediaTranscriptsJson()).isNull();

        InboundMessageEntity broken = new InboundMessageEntity("SM2", "t", "sender", "+1", 0, "not json 481516");
        SecretInboundRedaction.apply(broken, new ObjectMapper());
        assertThat(broken.getRawPayloadJson()).doesNotContain("481516");
    }
}
