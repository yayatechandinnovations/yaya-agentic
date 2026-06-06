package com.yayatechandinnovations.yayaagentic.recorder;

import com.yayatechandinnovations.yayaagentic.core.Turn;

import java.util.List;
import java.util.Map;

/**
 * Called by recorders (not by the engine) when {@link LoadOptions#summarized}
 * is true. Strategy is independent of storage tier — that's why this is
 * its own SPI. See design §16, q13.
 */
public interface ConversationSummarizer {

    SummarizedTurn summarize(List<Turn> window, SummarizationContext ctx);

    record SummarizedTurn(String text, Map<String, Object> metadata) {}

    record SummarizationContext(int budgetTokens, Map<String, Object> attributes) {}
}
