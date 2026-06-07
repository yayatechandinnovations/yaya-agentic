package com.yayatechandinnovations.yayaagentic.knowledge.chunker;

import com.yayatechandinnovations.yayaagentic.knowledge.IngestionPolicy;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeLoader.RawDocument;

import java.util.List;
import java.util.Map;

public interface Chunker {

    String name();

    List<Chunk> chunk(RawDocument document, IngestionPolicy policy);

    /**
     * One indexable chunk. {@code section} captures the heading path the
     * splitter resolved this chunk to, so citations can quote a meaningful
     * anchor instead of just an offset.
     */
    record Chunk(int index, String text, String section, Map<String, Object> metadata) {}
}
