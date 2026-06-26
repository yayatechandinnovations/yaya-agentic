package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function test of the 1600-char outbound splitter. The OutboundDispatcher
 * exposes the chunker as package-private for direct testing.
 */
class OutboundChunkingTest {

    @Test
    void short_body_emits_single_chunk() {
        List<String> chunks = OutboundDispatcher.splitIntoChunks("Hello", 1600);
        assertThat(chunks).containsExactly("Hello");
    }

    @Test
    void body_at_limit_emits_single_chunk() {
        String body = "a".repeat(1600);
        assertThat(OutboundDispatcher.splitIntoChunks(body, 1600)).containsExactly(body);
    }

    @Test
    void long_body_splits_on_whitespace_boundary() {
        StringBuilder sb = new StringBuilder();
        // 2400 chars total, alternating words + spaces
        for (int i = 0; i < 600; i++) sb.append("word ");
        List<String> chunks = OutboundDispatcher.splitIntoChunks(sb.toString(), 1600);
        assertThat(chunks).hasSizeGreaterThan(1);
        for (String c : chunks) {
            assertThat(c.length()).isLessThanOrEqualTo(1600);
        }
        // Reassembly should reproduce the original (up to whitespace
        // normalization on chunk boundaries).
        String reassembled = String.join(" ", chunks);
        assertThat(reassembled.replace(" ", ""))
                .isEqualTo(sb.toString().replace(" ", ""));
    }

    @Test
    void body_with_no_spaces_still_splits_hard() {
        String body = "x".repeat(2400);
        List<String> chunks = OutboundDispatcher.splitIntoChunks(body, 1600);
        assertThat(chunks).hasSizeGreaterThan(1);
        for (String c : chunks) {
            assertThat(c.length()).isLessThanOrEqualTo(1600);
        }
    }

    @Test
    void null_body_returns_one_empty_chunk() {
        assertThat(OutboundDispatcher.splitIntoChunks(null, 1600)).containsExactly("");
    }
}
