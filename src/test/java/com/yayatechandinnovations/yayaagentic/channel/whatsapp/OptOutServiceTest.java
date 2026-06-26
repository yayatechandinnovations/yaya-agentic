package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OptOutServiceTest {

    private final OptOutService svc = new OptOutService(null);

    @Test
    void classifies_stop_variants_case_insensitive() {
        assertThat(svc.classify("STOP")).isEqualTo(OptOutService.Classification.STOP);
        assertThat(svc.classify("stop")).isEqualTo(OptOutService.Classification.STOP);
        assertThat(svc.classify("UNSUB")).isEqualTo(OptOutService.Classification.STOP);
        assertThat(svc.classify("CANCEL")).isEqualTo(OptOutService.Classification.STOP);
        assertThat(svc.classify("Quit")).isEqualTo(OptOutService.Classification.STOP);
    }

    @Test
    void classifies_start_variants() {
        assertThat(svc.classify("START")).isEqualTo(OptOutService.Classification.START);
        assertThat(svc.classify("unstop")).isEqualTo(OptOutService.Classification.START);
        assertThat(svc.classify("SUBSCRIBE")).isEqualTo(OptOutService.Classification.START);
    }

    @Test
    void classifies_nothing_when_word_embedded_or_punctuated() {
        assertThat(svc.classify("please stop sending"))
                .isEqualTo(OptOutService.Classification.NONE);
        assertThat(svc.classify("stop!")).isEqualTo(OptOutService.Classification.NONE);
        assertThat(svc.classify("STOP this"))
                .isEqualTo(OptOutService.Classification.NONE);
    }

    @Test
    void classifies_nothing_for_null_or_blank() {
        assertThat(svc.classify(null)).isEqualTo(OptOutService.Classification.NONE);
        assertThat(svc.classify("")).isEqualTo(OptOutService.Classification.NONE);
        assertThat(svc.classify("   ")).isEqualTo(OptOutService.Classification.NONE);
    }
}
