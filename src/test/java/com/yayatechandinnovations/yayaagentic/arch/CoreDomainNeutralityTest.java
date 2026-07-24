package com.yayatechandinnovations.yayaagentic.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the CLAUDE.md non-negotiable: the framework core stays domain-neutral.
 * A business domain (the store) belongs in an app under {@code apps/}, never in
 * {@code com.yayatechandinnovations.yayaagentic.*}. Store vocabulary reaches the
 * agent only through generic seams — HTTP {@code sales.*} tools, the {@code
 * _uiHint} pass-through, and a generic {@link
 * com.yayatechandinnovations.yayaagentic.profile.ProfileExtension} client trait.
 *
 * <p>This scans source directly (not classes) so it also catches leaked
 * vocabulary in Javadoc. As S7 (docs/milestones/S7-decouple-store.md) turns each
 * slice green, entries drop out of {@link #KNOWN_VIOLATIONS}; the guard is done
 * when that set is empty.</p>
 */
class CoreDomainNeutralityTest {

    /** Packages that must not name the store domain. */
    private static final List<String> GUARDED_PACKAGES = List.of(
            "src/main/java/com/yayatechandinnovations/yayaagentic/profile",
            "src/main/java/com/yayatechandinnovations/yayaagentic/engine",
            "src/main/java/com/yayatechandinnovations/yayaagentic/api",
            "src/main/java/com/yayatechandinnovations/yayaagentic/core",
            "src/main/java/com/yayatechandinnovations/yayaagentic/demos");

    /**
     * Store-domain tokens with no business in a domain-neutral framework. Bare
     * "catalog" is deliberately absent: the framework's tool/capability registry
     * ({@code M0Catalog}, {@code CatalogMapper}) legitimately owns that word.
     */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "commerce|storefront|\\bpsp\\b|checkout|\\bcart\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Files still carrying store vocabulary. Empty: S7 removed the last of it.
     * A new leak fails the guard — add nothing here to make it pass; move the
     * domain into an app under {@code apps/} instead (CLAUDE.md).
     */
    private static final Set<String> KNOWN_VIOLATIONS = Set.of();

    @Test
    void coreHasNoStoreDomainVocabulary() throws IOException {
        List<String> leaks = new ArrayList<>();
        for (String pkg : GUARDED_PACKAGES) {
            Path root = Path.of(pkg);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java"))
                     .forEach(p -> scan(p, leaks));
            }
        }
        assertTrue(leaks.isEmpty(),
                "Store-domain vocabulary leaked into the neutral core (see "
                + "docs/milestones/S7-decouple-store.md):\n" + String.join("\n", leaks));
    }

    private static void scan(Path file, List<String> leaks) {
        String rel = file.toString().replace('\\', '/');
        if (KNOWN_VIOLATIONS.contains(rel)) return;
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        for (int i = 0; i < lines.size(); i++) {
            if (FORBIDDEN.matcher(lines.get(i).toLowerCase(Locale.ROOT)).find()) {
                leaks.add(rel + ":" + (i + 1) + "  " + lines.get(i).trim());
            }
        }
    }
}
