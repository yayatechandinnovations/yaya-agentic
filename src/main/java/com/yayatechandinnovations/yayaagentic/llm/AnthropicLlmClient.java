package com.yayatechandinnovations.yayaagentic.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin Spring-AI wrapper. Activated by {@code yaya.agentic.llm.provider=anthropic}.
 * M0 streams plain tokens only; tool-calling integration lands in M2.
 */
@Component
@ConditionalOnProperty(name = "yaya.agentic.llm.provider", havingValue = "anthropic")
public class AnthropicLlmClient implements LlmClient {

    private final ChatModel chatModel;

    public AnthropicLlmClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Flux<TokenChunk> stream(LlmRequest request) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(request.systemPrompt()));
        for (Message m : request.history()) {
            messages.add(switch (m.role()) {
                case USER -> new UserMessage(m.content());
                case ASSISTANT -> new AssistantMessage(m.content());
            });
        }
        messages.add(new UserMessage(request.userMessage()));

        return chatModel.stream(new Prompt(messages))
                .mapNotNull(resp -> resp.getResult() == null ? null : resp.getResult().getOutput())
                .mapNotNull(AssistantMessage::getText)
                .filter(text -> !text.isEmpty())
                .map(TokenChunk::new);
    }
}
