package com.yayatechandinnovations.yayaagentic.knowledge.extract;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Turns uploaded document bytes into plain text for ingestion. Page-aware: a
 * document is a sequence of {@link Page}s so retrieval can cite a specific page
 * ("returns.pdf, p.4"). Flow formats (HTML, markdown, plain text) collapse to a
 * single page ({@code pageNumber == 1}).
 *
 * <p>Design: {@code docs/design/knowledge-file-upload-design.md} §7–§8.
 * Registered as a {@code List<TextExtractor>}; first {@link #supports} wins —
 * mirrors the {@code KnowledgeLoader} registry.
 */
public interface TextExtractor {

    boolean supports(String contentType);

    Extracted extract(InputStream in, String filename, String contentType);

    record Extracted(List<Page> pages, Map<String, Object> metadata) {}

    record Page(int pageNumber, String text) {}
}
