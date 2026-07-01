package com.yayatechandinnovations.yayaagentic.knowledge.blob;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Tuning for knowledge-file uploads and storage — bound from
 * {@code yaya.agentic.knowledge}. Kept as its own {@code @ConfigurationProperties}
 * (rather than a nested record on {@code YayaAgenticProperties}) so the upload
 * subsystem owns its own knobs and callers constructing the core props in tests
 * don't have to know about them.
 *
 * <p>Caps mirror {@code docs/design/knowledge-file-upload-design.md} §11.
 */
@ConfigurationProperties(prefix = "yaya.agentic.knowledge")
public record KnowledgeUploadProperties(
        String blobStore,               // db | minio  (only "db" implemented in this increment)
        Long maxFileBytes,
        Long maxRequestBytes,
        Long maxTenantCorpusBytes,
        List<String> allowedContentTypes,
        boolean malwareScanEnabled
) {
    private static final long MIB = 1024L * 1024L;

    public KnowledgeUploadProperties {
        if (blobStore == null || blobStore.isBlank()) blobStore = "db";
        if (maxFileBytes == null) maxFileBytes = 25L * MIB;
        if (maxRequestBytes == null) maxRequestBytes = 100L * MIB;
        if (maxTenantCorpusBytes == null) maxTenantCorpusBytes = 2L * 1024 * MIB; // 2 GiB
        if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
            allowedContentTypes = List.of(
                    "text/plain",
                    "text/markdown",
                    "text/html",
                    "application/pdf",
                    "application/rtf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.oasis.opendocument.text");
        }
    }

    public boolean isAllowed(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase();
        int semi = ct.indexOf(';');
        if (semi >= 0) ct = ct.substring(0, semi).trim();
        return allowedContentTypes.contains(ct);
    }
}
