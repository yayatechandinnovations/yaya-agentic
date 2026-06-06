package com.yayatechandinnovations.yayaagentic.knowledge;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * Where a knowledge source's documents live. Loaders are selected by
 * matching the sealed variant.
 */
public sealed interface SourceLocation
        permits SourceLocation.LocalPath,
                SourceLocation.HttpUrl,
                SourceLocation.S3Prefix,
                SourceLocation.GitRepo,
                SourceLocation.Inline {

    record LocalPath(Path root, String includeGlob, String excludeGlob) implements SourceLocation {}

    record HttpUrl(URI base, List<URI> seeds) implements SourceLocation {}

    record S3Prefix(String bucket, String prefix) implements SourceLocation {}

    record GitRepo(URI repo, String ref, String includeGlob) implements SourceLocation {}

    record Inline(List<DocumentBlob> docs) implements SourceLocation {
        public record DocumentBlob(String id, String contentType, String text) {}
    }
}
