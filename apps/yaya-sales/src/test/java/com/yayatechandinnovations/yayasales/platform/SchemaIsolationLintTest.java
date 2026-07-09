package com.yayatechandinnovations.yayasales.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decision 5 / §12.4 (S0): yaya-sales owns the {@code yaya_sales} schema and
 * must NEVER reference another — no cross-schema FKs or joins — so it lifts to
 * its own Postgres instance without query changes. This build-time lint fails
 * if any Flyway migration schema-qualifies an identifier with anything other
 * than {@code yaya_sales}. Mirrors yaya-identity's guard.
 */
class SchemaIsolationLintTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Set<String> ALLOWED_SCHEMAS = Set.of("yaya_sales");

    // Matches a schema-qualified identifier: <ident>.<ident>, capturing the qualifier.
    private static final Pattern QUALIFIED = Pattern.compile(
            "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\.\\s*[a-zA-Z_][a-zA-Z0-9_]*");

    @Test
    void noMigrationReferencesAForeignSchema() throws IOException {
        assertTrue(Files.isDirectory(MIGRATIONS),
                "migrations dir not found at " + MIGRATIONS.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                String sql = stripComments(Files.readString(f, StandardCharsets.UTF_8));
                Matcher m = QUALIFIED.matcher(sql);
                while (m.find()) {
                    String qualifier = m.group(1).toLowerCase();
                    if (!ALLOWED_SCHEMAS.contains(qualifier)) {
                        violations.add(f.getFileName() + ": schema-qualified reference '"
                                + m.group() + "' (qualifier '" + qualifier + "')");
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("cross-schema references found — yaya-sales must stay schema-isolated (decision 5):\n  "
                    + String.join("\n  ", violations));
        }
    }

    /** Strip -- line comments so prose (e.g. "design §5.5") never trips the lint. */
    private static String stripComments(String sql) {
        return sql.replaceAll("--[^\n]*", "");
    }
}
