package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbound safety net for the "3x message" bug: if the engine flux still
 * contains an exact whole-number repetition of one block, collapse it to a
 * single copy before sending. Must be conservative — never touch
 * near-duplicates or legitimately short repetition.
 */
class OutboundDispatcherCollapseTest {

    private static final String BLOCK =
            "Vamos con eso. Déjame armar el borrador. Lo siento, no puedo procesar la venta ahora — "
            + "no hay una estación activa configurada. Pásate por una banca para hacer tu jugada.";

    @Test
    void collapses_a_tripled_block_to_one() {
        String tripled = BLOCK + BLOCK + BLOCK;
        assertThat(OutboundDispatcher.collapseRepeatedBody(tripled)).isEqualTo(BLOCK.strip());
    }

    @Test
    void collapses_a_doubled_block_to_one() {
        assertThat(OutboundDispatcher.collapseRepeatedBody(BLOCK + BLOCK)).isEqualTo(BLOCK.strip());
    }

    @Test
    void leaves_a_single_block_untouched() {
        assertThat(OutboundDispatcher.collapseRepeatedBody(BLOCK)).isEqualTo(BLOCK);
    }

    @Test
    void does_not_touch_near_duplicates() {
        // Second copy differs by one character — not an exact repetition.
        String almost = BLOCK + BLOCK.replace("borrador", "borradorr");
        assertThat(OutboundDispatcher.collapseRepeatedBody(almost)).isEqualTo(almost);
    }

    @Test
    void does_not_collapse_short_repetition() {
        // "ja " repeated is below MIN_DEDUP_UNIT — legitimate, must survive.
        String laughter = "ja ".repeat(20);
        assertThat(OutboundDispatcher.collapseRepeatedBody(laughter)).isEqualTo(laughter);
    }

    @Test
    void null_and_blank_are_safe() {
        assertThat(OutboundDispatcher.collapseRepeatedBody(null)).isNull();
        assertThat(OutboundDispatcher.collapseRepeatedBody("")).isEqualTo("");
    }
}
