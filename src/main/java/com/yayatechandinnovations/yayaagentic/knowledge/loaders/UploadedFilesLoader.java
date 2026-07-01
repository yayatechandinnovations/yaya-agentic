package com.yayatechandinnovations.yayaagentic.knowledge.loaders;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeLoader;
import com.yayatechandinnovations.yayaagentic.knowledge.SourceLocation;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.BlobStore;
import com.yayatechandinnovations.yayaagentic.knowledge.extract.TextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads {@link SourceLocation.Uploaded} sources: for each referenced blob, pull
 * bytes from the {@link BlobStore}, extract text with a {@link TextExtractor},
 * and emit one {@link RawDocument} <em>per page</em>. Emitting per-page (rather
 * than one document per file) is what gives retrieval page-level citations for
 * free — every chunk inherits its document's {@code page} without the chunker or
 * orchestrator needing to track char offsets (design §8).
 */
@Component
public class UploadedFilesLoader implements KnowledgeLoader {

    private static final Logger LOG = LoggerFactory.getLogger(UploadedFilesLoader.class);

    private final BlobStore blobStore;
    private final List<TextExtractor> extractors;

    public UploadedFilesLoader(BlobStore blobStore, List<TextExtractor> extractors) {
        this.blobStore = blobStore;
        this.extractors = extractors;
    }

    @Override
    public boolean supports(SourceLocation location) {
        return location instanceof SourceLocation.Uploaded;
    }

    @Override
    public Stream<RawDocument> load(SourceLocation location, IngestionContext ctx) {
        SourceLocation.Uploaded uploaded = (SourceLocation.Uploaded) location;
        TenantId tenant = tenantFrom(ctx);

        List<RawDocument> docs = new ArrayList<>();
        for (SourceLocation.Uploaded.UploadedFile file : uploaded.files()) {
            // Defense in depth: never ingest bytes that weren't confirmed clean.
            blobStore.stat(tenant, file.blobId()).ifPresent(meta -> {
                if ("INFECTED".equals(meta.scanStatus())) {
                    throw new IllegalStateException("refusing infected blob " + file.blobId());
                }
            });

            InputStream in = blobStore.open(tenant, file.blobId())
                    .orElseThrow(() -> new IllegalStateException(
                            "missing blob " + file.blobId() + " for tenant " + tenant.value()));

            TextExtractor extractor = extractorFor(file.contentType());
            TextExtractor.Extracted extracted = extractor.extract(in, file.filename(), file.contentType());

            for (TextExtractor.Page page : extracted.pages()) {
                docs.add(toRawDocument(file, page));
            }
            if (extracted.pages().isEmpty()) {
                LOG.warn("no extractable text in {} (blob {})", file.filename(), file.blobId());
            }
        }
        return docs.stream();
    }

    private RawDocument toRawDocument(SourceLocation.Uploaded.UploadedFile file, TextExtractor.Page page) {
        // Per-page identity keeps idempotency (content_hash) and citations page-precise.
        String docId = file.blobId() + "#p" + page.pageNumber();
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "uploaded");
        meta.put("blobId", file.blobId());
        meta.put("filename", file.filename());
        meta.put("contentType", file.contentType());
        meta.put("page", page.pageNumber());
        return new RawDocument(
                docId,
                "blob:" + file.blobId() + "#page=" + page.pageNumber(),
                file.filename(),
                file.contentType(),
                page.text(),
                meta);
    }

    private TextExtractor extractorFor(String contentType) {
        return extractors.stream()
                .filter(e -> e.supports(contentType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no TextExtractor for content type " + contentType));
    }

    private static TenantId tenantFrom(IngestionContext ctx) {
        Object t = ctx == null || ctx.attributes() == null ? null : ctx.attributes().get("tenantId");
        if (t == null) throw new IllegalStateException(
                "UploadedFilesLoader requires tenantId in the IngestionContext");
        return new TenantId(t.toString());
    }
}
