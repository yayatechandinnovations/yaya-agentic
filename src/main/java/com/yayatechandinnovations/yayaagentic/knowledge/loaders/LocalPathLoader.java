package com.yayatechandinnovations.yayaagentic.knowledge.loaders;

import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeLoader;
import com.yayatechandinnovations.yayaagentic.knowledge.SourceLocation;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Walks a {@link SourceLocation.LocalPath} and emits one {@link RawDocument}
 * per matching file. Markdown / plain-text only in M2.5-A — PDF and HTML
 * parsers come in M2.5-B once the round-trip is proven.
 */
@Component
public class LocalPathLoader implements KnowledgeLoader {

    private static final long MAX_FILE_SIZE_BYTES = 8L * 1024 * 1024; // 8 MiB safety cap

    @Override
    public boolean supports(SourceLocation location) {
        return location instanceof SourceLocation.LocalPath;
    }

    @Override
    public Stream<RawDocument> load(SourceLocation location, IngestionContext ctx) {
        SourceLocation.LocalPath lp = (SourceLocation.LocalPath) location;
        Path root = lp.root();
        if (root == null || !Files.isDirectory(root)) return Stream.empty();

        PathMatcher include = matcher(lp.includeGlob(), "glob:**/*.{md,markdown,txt}");
        PathMatcher exclude = lp.excludeGlob() == null || lp.excludeGlob().isBlank()
                ? null : matcher(lp.excludeGlob(), null);

        try {
            return Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(p -> include.matches(p))
                    .filter(p -> exclude == null || !exclude.matches(p))
                    .filter(p -> {
                        try { return Files.size(p) <= MAX_FILE_SIZE_BYTES; }
                        catch (IOException e) { return false; }
                    })
                    .map(p -> toRawDocument(root, p));
        } catch (IOException e) {
            throw new UncheckedIOException("walk failed: " + root, e);
        }
    }

    private static RawDocument toRawDocument(Path root, Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String relative = root.relativize(file).toString();
            Map<String, Object> meta = new HashMap<>();
            meta.put("loader", "local-path");
            meta.put("path", relative);
            return new RawDocument(
                    relative,
                    file.toUri().toString(),
                    file.getFileName().toString(),
                    contentTypeFor(file),
                    text,
                    meta);
        } catch (IOException e) {
            throw new UncheckedIOException("read failed: " + file, e);
        }
    }

    private static PathMatcher matcher(String glob, String fallback) {
        String pattern = (glob == null || glob.isBlank()) ? fallback : glob;
        if (pattern == null) return p -> true;
        if (!pattern.startsWith("glob:") && !pattern.startsWith("regex:")) {
            pattern = "glob:" + pattern;
        }
        return FileSystems.getDefault().getPathMatcher(pattern);
    }

    private static String contentTypeFor(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".md") || name.endsWith(".markdown")) return "text/markdown";
        return "text/plain";
    }
}
