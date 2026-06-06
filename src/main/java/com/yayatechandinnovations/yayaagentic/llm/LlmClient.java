package com.yayatechandinnovations.yayaagentic.llm;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Single LLM façade the engine talks to. Implementations choose how to
 * produce a stream of token chunks given a system + history prompt.
 */
public interface LlmClient {

    Flux<TokenChunk> stream(LlmRequest request);

    record LlmRequest(String systemPrompt, List<Message> history, String userMessage) {}

    record Message(Role role, String content) {
        public enum Role { USER, ASSISTANT }
    }

    record TokenChunk(String text) {}
}
