package com.yayatechandinnovations.yayaagentic.channel.sms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Segment-counting rules (§5.1). These numbers drive billing and the long-reply
 * eval, so they're pinned explicitly.
 */
class SmsSegmenterTest {

    @Test
    void short_ascii_is_one_gsm7_segment() {
        var s = SmsSegmenter.analyze("Your order shipped.");
        assertThat(s.encoding()).isEqualTo(SmsSegmenter.Encoding.GSM_7);
        assertThat(s.segmentCount()).isEqualTo(1);
    }

    @Test
    void gsm7_boundary_at_160() {
        assertThat(SmsSegmenter.segmentCount("a".repeat(160))).isEqualTo(1);
        assertThat(SmsSegmenter.segmentCount("a".repeat(161))).isEqualTo(2);   // 153/seg once concatenated
        assertThat(SmsSegmenter.segmentCount("a".repeat(306))).isEqualTo(2);
        assertThat(SmsSegmenter.segmentCount("a".repeat(307))).isEqualTo(3);
    }

    @Test
    void empty_body_counts_as_one_segment() {
        assertThat(SmsSegmenter.segmentCount("")).isEqualTo(1);
        assertThat(SmsSegmenter.segmentCount(null)).isEqualTo(1);
    }

    @Test
    void spanish_enye_stays_gsm7_but_accented_vowels_force_ucs2() {
        // ñ/Ñ are in the GSM-7 basic alphabet; á is not.
        assertThat(SmsSegmenter.analyze("El niño").encoding()).isEqualTo(SmsSegmenter.Encoding.GSM_7);
        assertThat(SmsSegmenter.analyze("está").encoding()).isEqualTo(SmsSegmenter.Encoding.UCS_2);
    }

    @Test
    void ucs2_boundary_at_70() {
        // 'é' is GSM-7; use 'á' (UCS-2-forcing) to build a UCS-2 body
        assertThat(SmsSegmenter.analyze("á".repeat(70)).segmentCount()).isEqualTo(1);
        assertThat(SmsSegmenter.analyze("á".repeat(71)).segmentCount()).isEqualTo(2);   // 67/seg
    }

    @Test
    void emoji_is_ucs2() {
        var s = SmsSegmenter.analyze("Winner! 🎉");
        assertThat(s.encoding()).isEqualTo(SmsSegmenter.Encoding.UCS_2);
        assertThat(s.segmentCount()).isEqualTo(1);
    }

    @Test
    void gsm7_extension_chars_cost_two_septets() {
        // 80 '€' = 160 septets = 1 segment; 81 = 162 septets = 2 segments
        assertThat(SmsSegmenter.segmentCount("€".repeat(80))).isEqualTo(1);
        assertThat(SmsSegmenter.segmentCount("€".repeat(81))).isEqualTo(2);
    }
}
