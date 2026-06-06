package com.yayatechandinnovations.yayaagentic.knowledge;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Ingestion plug-in. Concrete loaders match on {@link SourceLocation} variants
 * (LocalPath, HttpUrl, S3Prefix, GitRepo, Inline).
 */
public interface KnowledgeLoader {

    boolean supports(SourceLocation location);

    Stream<RawDocument> load(SourceLocation location, IngestionContext ctx);

    record RawDocument(String id, String uri, String title, String contentType,
                       String text, Map<String, Object> metadata) {}

    record IngestionContext(String correlationId, Map<String, Object> attributes) {}
}
