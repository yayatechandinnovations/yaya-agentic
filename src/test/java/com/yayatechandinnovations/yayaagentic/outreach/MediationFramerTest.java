package com.yayatechandinnovations.yayaagentic.outreach;

import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §9.1 Rule 1: the framing (inspection) turn runs with ZERO tools. */
class MediationFramerTest {

    @Test
    void framesWithEmptyToolsetAndReturnsModelText() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.stream(any())).thenReturn(Flux.just(
                new LlmClient.LlmEvent.TokenChunk("Cash Register #3 asks you to confirm 1000.")));
        MediationFramer framer = new MediationFramer(llm);

        String out = framer.frame("Cash Register #3", "closure", "confirm 1000");
        assertThat(out).isEqualTo("Cash Register #3 asks you to confirm 1000.");

        // The load-bearing property: no tools were offered on the inspection turn.
        ArgumentCaptor<LlmClient.LlmRequest> req = ArgumentCaptor.forClass(LlmClient.LlmRequest.class);
        verify(llm).stream(req.capture());
        assertThat(req.getValue().availableTools()).isEmpty();
    }

    @Test
    void fallsBackToTemplateWhenLlmFails() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.stream(any())).thenReturn(Flux.error(new RuntimeException("llm down")));
        MediationFramer framer = new MediationFramer(llm);

        String out = framer.frame("Cash Register #3", "closure", "confirm 1000");
        assertThat(out).contains("Cash Register #3").contains("confirm 1000");
    }
}
