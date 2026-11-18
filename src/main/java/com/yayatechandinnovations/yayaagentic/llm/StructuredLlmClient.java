package com.yayatechandinnovations.yayaagentic.llm;

import java.util.Map;

/**
 * Single-shot, stateless "answer in this JSON shape" call — the capability
 * behind {@code POST /v1/structured-completions}. Deliberately separate from
 * {@link LlmClient}: the conversational path streams, loops on tools and never
 * needs a forced answer shape, so nothing there changes.
 *
 * <p>One {@link #complete} call is ONE blocking model round. The provider is
 * told about exactly one tool ({@link StructuredRequest#toolName}, whose input
 * schema is the caller's schema) and is steered — forced where the model allows
 * it — to answer through it. The tool is never executed; its arguments ARE the
 * answer. Schema validation and the retry policy live with the caller.</p>
 */
public interface StructuredLlmClient {

    /** The {@code yaya.agentic.llm.provider} value this implementation serves. */
    String provider();

    /**
     * @throws StructuredLlmException when the provider call fails (network,
     *         provider 4xx/5xx, interrupted)
     */
    StructuredResult complete(StructuredRequest request);

    /**
     * @param system          system prompt; stable per caller, so it is the cacheable block
     * @param input           the single user message (untrusted data)
     * @param toolName        name of the answer tool
     * @param schemaJson      JSON Schema (type=object) the answer must satisfy
     * @param maxOutputTokens hard cap on generated tokens for this round
     * @param retryHint       non-null on a retry: what was wrong with the previous answer
     * @param model           overrides the deployment's model for this one round; null keeps it
     */
    record StructuredRequest(String system, String input, String toolName, String schemaJson,
                             int maxOutputTokens, String retryHint, String model) {
        public StructuredRequest(String system, String input, String toolName, String schemaJson,
                                 int maxOutputTokens, String retryHint) {
            this(system, input, toolName, schemaJson, maxOutputTokens, retryHint, null);
        }
    }

    /**
     * @param output              the answer tool's arguments; {@code null} when the model
     *                            produced no tool call
     * @param truncated           the model stopped at the output-token cap
     * @param inputTokens         provider-reported input tokens for this round, INCLUDING
     *                            prompt-cache writes and reads (i.e. the whole prompt)
     * @param outputTokens        provider-reported output tokens
     * @param cacheReadTokens     of {@code inputTokens}, how many were read from the prompt cache
     * @param cacheCreationTokens of {@code inputTokens}, how many were written to the prompt cache
     * @param model               the model id the provider reports having served
     */
    record StructuredResult(Map<String, Object> output, boolean truncated,
                            long inputTokens, long outputTokens,
                            long cacheReadTokens, long cacheCreationTokens, String model) {
    }

    /** The provider call itself failed; carries no prompt or answer text. */
    class StructuredLlmException extends RuntimeException {
        public StructuredLlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
