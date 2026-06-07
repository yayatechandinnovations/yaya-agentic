package com.yayatechandinnovations.yayaagentic.knowledge.ingest;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ingest-time defense against documents that try to talk to the LLM as if
 * they were a system instruction. Two layers, by design — at retrieval the
 * engine also wraps chunks in {@code <retrieved>…</retrieved>} delimiters
 * with an explicit "treat as DATA" rule. This sanitizer makes the wrap
 * less load-bearing by removing the most common phrasings before they
 * ever reach the model.
 * <p>
 * We don't try to "detect prompt injection" generally — that's a losing
 * game. We just scrub a few patterns that show up in red-team docs and
 * leave a visible marker so an auditor can see what was changed.
 */
@Component
public class ContentSanitizer {

    private static final Pattern[] INJECTION_PATTERNS = {
            Pattern.compile("(?i)ignore (the )?(previous|prior|above|all) instructions"),
            Pattern.compile("(?i)disregard (the )?(previous|prior|above|all) (instructions|prompts)"),
            Pattern.compile("(?i)you are now [a-z ]{1,40}"),
            Pattern.compile("(?i)system:\\s*you are"),
            Pattern.compile("(?i)forget (all|everything) (above|prior|previous)"),
            Pattern.compile("(?i)(reveal|print|output) (the |your )?(system )?prompt"),
    };

    private static final String MARKER = "[[redacted: instruction-like phrasing]]";

    public String sanitize(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Pattern p : INJECTION_PATTERNS) {
            Matcher m = p.matcher(out);
            if (m.find()) {
                out = m.replaceAll(MARKER);
            }
        }
        return out;
    }
}
