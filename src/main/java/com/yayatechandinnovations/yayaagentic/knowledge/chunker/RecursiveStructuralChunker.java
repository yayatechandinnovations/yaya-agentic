package com.yayatechandinnovations.yayaagentic.knowledge.chunker;

import com.yayatechandinnovations.yayaagentic.knowledge.IngestionPolicy;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeLoader.RawDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recursive structural splitter — markdown headings first, then paragraphs,
 * then sentences, falling back to a hard window. Keeps the active heading
 * path on every chunk so citations can quote "Returns › Policy" instead of
 * "chunk #14". The split is char-based (not token-based) — we treat
 * {@link IngestionPolicy#chunkSize()} and {@code chunkOverlap} as
 * characters; tokens are roughly 4 chars per English so 900 chars ≈ 225
 * tokens, which fits well inside any modern context window.
 */
@Component
public class RecursiveStructuralChunker implements Chunker {

    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})\\s+(.+)$");
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z\"\\(])");

    @Override
    public String name() { return "recursive-structural"; }

    @Override
    public List<Chunk> chunk(RawDocument document, IngestionPolicy policy) {
        int size = Math.max(200, policy == null ? 900 : policy.chunkSize());
        int overlap = Math.max(0, policy == null ? 100 : Math.min(policy.chunkOverlap(), size / 2));
        String text = document.text() == null ? "" : document.text();
        if (text.isBlank()) return List.of();

        List<Section> sections = splitByHeadings(text);
        List<Chunk> out = new ArrayList<>();
        int idx = 0;
        for (Section section : sections) {
            for (String paragraph : PARAGRAPH_SPLIT.split(section.body())) {
                if (paragraph.isBlank()) continue;
                if (paragraph.length() <= size) {
                    out.add(toChunk(idx++, paragraph.strip(), section.path()));
                    continue;
                }
                List<String> sentences = Arrays.asList(SENTENCE_SPLIT.split(paragraph));
                List<String> windows = packSentences(sentences, size, overlap);
                for (String w : windows) {
                    if (w.isBlank()) continue;
                    out.add(toChunk(idx++, w.strip(), section.path()));
                }
            }
        }

        if (out.isEmpty()) {
            // Pathological input (no headings, no double newlines, no sentence
            // boundaries) — fall back to a hard char window so the document
            // still gets indexed.
            for (int i = 0; i < text.length(); i += (size - overlap)) {
                int end = Math.min(text.length(), i + size);
                out.add(toChunk(idx++, text.substring(i, end).strip(), ""));
                if (end == text.length()) break;
            }
        }
        return out;
    }

    private static List<String> packSentences(List<String> sentences, int size, int overlap) {
        List<String> packed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String s : sentences) {
            if (current.length() + s.length() + 1 > size && current.length() > 0) {
                packed.add(current.toString());
                if (overlap > 0 && current.length() > overlap) {
                    current = new StringBuilder(current.substring(current.length() - overlap));
                } else {
                    current = new StringBuilder();
                }
            }
            current.append(s).append(' ');
        }
        if (current.length() > 0) packed.add(current.toString());
        return packed;
    }

    private static Chunk toChunk(int index, String text, String sectionPath) {
        Map<String, Object> meta = new HashMap<>();
        if (sectionPath != null && !sectionPath.isBlank()) meta.put("section", sectionPath);
        return new Chunk(index, text, sectionPath, meta);
    }

    private record Section(String path, String body) {}

    private static List<Section> splitByHeadings(String text) {
        List<Section> sections = new ArrayList<>();
        Matcher m = HEADING.matcher(text);
        int lastEnd = 0;
        String[] headingStack = new String[7];
        String currentPath = "";
        boolean foundHeading = false;

        while (m.find()) {
            foundHeading = true;
            if (m.start() > lastEnd) {
                sections.add(new Section(currentPath, text.substring(lastEnd, m.start())));
            }
            int level = m.group(1).length();
            headingStack[level] = m.group(2).trim();
            for (int i = level + 1; i < headingStack.length; i++) headingStack[i] = null;
            currentPath = buildPath(headingStack);
            lastEnd = m.end();
        }
        if (foundHeading) {
            sections.add(new Section(currentPath, text.substring(lastEnd)));
        } else {
            sections.add(new Section("", text));
        }
        return sections;
    }

    private static String buildPath(String[] stack) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < stack.length; i++) {
            if (stack[i] == null) continue;
            if (sb.length() > 0) sb.append(" › ");
            sb.append(stack[i]);
        }
        return sb.toString();
    }
}
