package com.yayatechandinnovations.yayaagentic.knowledge;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Duration;
import java.util.List;

public record RetrievalResult(List<RetrievedChunk> chunks, Trace trace) {

    public record Trace(
            List<Ids.KnowledgeSourceId> sourcesConsidered,
            List<Ids.KnowledgeSourceId> sourcesDenied,
            String rewrittenQuery,
            Duration latency
    ) {}
}
