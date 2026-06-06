package com.yayatechandinnovations.yayaagentic.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * Default LLM client. Streams a deterministic, profile-agnostic reply so
 * tests and local dev don't need an API key. Real provider work is M2+.
 */
@Component
@ConditionalOnProperty(name = "yaya.agentic.llm.provider", havingValue = "stub", matchIfMissing = true)
public class StubLlmClient implements LlmClient {

    @Override
    public Flux<TokenChunk> stream(LlmRequest request) {
        List<String> chunks = scriptFor(request.userMessage());
        return Flux.fromIterable(chunks)
                .delayElements(Duration.ofMillis(40))
                .map(TokenChunk::new);
    }

    private List<String> scriptFor(String userMessage) {
        String trimmed = userMessage == null ? "" : userMessage.trim();
        if (trimmed.toLowerCase().startsWith("/echo ") || trimmed.toLowerCase().startsWith("echo ")) {
            String arg = trimmed.substring(trimmed.toLowerCase().indexOf("echo ") + 5).trim();
            return List.of("You said: ", "\"" + arg + "\"", ". ", "Anything else?");
        }
        if (trimmed.equalsIgnoreCase("hello") || trimmed.equalsIgnoreCase("hi")) {
            return List.of("Hi! ", "I'm Yaya. ", "Try ", "saying ", "\"echo hello\" ", "to see ", "the tool ", "dispatcher ", "in action.");
        }
        return List.of("I can ", "echo ", "what you ", "say. ", "Try ", "\"echo " + trimmed + "\".");
    }
}
