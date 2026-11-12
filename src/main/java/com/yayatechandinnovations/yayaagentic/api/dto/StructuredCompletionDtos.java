package com.yayatechandinnovations.yayaagentic.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** Wire shapes of {@code POST /v1/structured-completions} (docs/guides/structured-completions.md). */
public final class StructuredCompletionDtos {

    private StructuredCompletionDtos() {
    }

    public record Response(Map<String, Object> output, Usage usage, String model) {
    }

    /** Provider-reported token counts, summed over every model call made for the request. */
    public record Usage(long inputTokens, long outputTokens) {
    }

    public record Error(String error, @JsonInclude(JsonInclude.Include.NON_NULL) String detail) {
    }
}
