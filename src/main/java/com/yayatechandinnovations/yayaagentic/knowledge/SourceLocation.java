package com.yayatechandinnovations.yayaagentic.knowledge;

import java.net.URI;
import java.util.List;

/**
 * Where a knowledge source's documents live. Loaders are selected by
 * matching the sealed variant.
 *
 * <p>There is deliberately no server-filesystem variant: reading arbitrary
 * host paths chosen by an operator is an arbitrary-file-read / path-traversal
 * risk (a malicious or careless operator could point a source at server
 * secrets). Documents come in through {@link Uploaded} (managed storage) or
 * {@link Inline}; the platform never reads a caller-supplied local path.
 */
public sealed interface SourceLocation
        permits SourceLocation.HttpUrl,
                SourceLocation.S3Prefix,
                SourceLocation.GitRepo,
                SourceLocation.Inline,
                SourceLocation.Uploaded {

    record HttpUrl(URI base, List<URI> seeds) implements SourceLocation {}

    record S3Prefix(String bucket, String prefix) implements SourceLocation {}

    record GitRepo(URI repo, String ref, String includeGlob) implements SourceLocation {}

    record Inline(List<DocumentBlob> docs) implements SourceLocation {
        public record DocumentBlob(String id, String contentType, String text) {}
    }

    /**
     * Documents uploaded through the admin console and held by the
     * {@code BlobStore}. Each entry is a *reference* to bytes (a blobId),
     * never the bytes themselves — so a source version stays small and the
     * physical storage backend (Postgres bytea / MinIO) is invisible to the
     * operator. See {@code docs/design/knowledge-file-upload-design.md} §5.
     */
    record Uploaded(List<UploadedFile> files) implements SourceLocation {
        public record UploadedFile(
                String blobId,
                String filename,
                String contentType,
                long sizeBytes,
                String sha256) {}
    }
}
