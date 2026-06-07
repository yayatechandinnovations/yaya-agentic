package com.yayatechandinnovations.yayaagentic.knowledge.embedding;

import java.util.List;

/**
 * Thin port over whatever embedding backend is configured. M2.5-A wires
 * one Spring-AI-backed implementation (OpenAI text-embedding-3-small);
 * future versions may swap in a local model or a different provider
 * without rippling through the ingestion pipeline.
 */
public interface EmbeddingService {

    int dimension();

    String modelId();

    /** Embed one text. Returns a float vector of length {@link #dimension()}. */
    float[] embed(String text);

    /** Embed many. Implementations may batch for throughput; ordering is preserved. */
    List<float[]> embedBatch(List<String> texts);
}
