package com.yayatechandinnovations.yayaagentic.knowledge;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Map;

public record RetrievedChunk(
        String chunkId,
        Ids.KnowledgeSourceId source,
        String documentId,
        String text,
        Map<String, Object> metadata,
        double score
) {}
