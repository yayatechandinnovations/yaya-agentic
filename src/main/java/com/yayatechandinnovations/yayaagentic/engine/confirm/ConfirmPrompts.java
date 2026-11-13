package com.yayatechandinnovations.yayaagentic.engine.confirm;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The confirm frame's user-facing copy (rules R1/R2 of
 * whatsapp-ops-integration-design.md §5): the tool's operator-authored
 * {@code confirmPrompt}, interpolated with the call's OWN args, or a
 * language-appropriate neutral question.
 *
 * <p>Interpolation reads only the proposed call's arguments — never tool
 * results or retrieved text — and each value is flattened to one short line,
 * so an arg cannot smuggle a second question or a fake instruction into the
 * frame.
 */
public final class ConfirmPrompts {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.]+)\\}");
    private static final int MAX_VALUE_CHARS = 80;

    private ConfirmPrompts() {}

    public static String render(String template, Map<String, Object> args, String language) {
        if (template == null || template.isBlank()) return fallbackQuestion(language);
        Matcher m = PLACEHOLDER.matcher(template.trim());
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(valueOf(args, m.group(1))));
        }
        m.appendTail(out);
        String rendered = out.toString().replaceAll("[ \\t]{2,}", " ").replace(" ?", "?").trim();
        return rendered.isEmpty() ? fallbackQuestion(language) : rendered;
    }

    public static String fallbackQuestion(String language) {
        return isSpanish(language) ? "¿Continúo?" : "Do you want to continue?";
    }

    public static String cancelled(String language) {
        return isSpanish(language) ? "Listo, no hice nada." : "OK — cancelled.";
    }

    public static String staleFrame(String language) {
        return isSpanish(language)
                ? "Esa confirmación ya no está vigente. Dime de nuevo qué quieres hacer."
                : "That confirmation is no longer pending. Tell me again what you want to do.";
    }

    public static String confirmLabel(String language) {
        return isSpanish(language) ? "Confirmar" : "Confirm";
    }

    public static String cancelLabel(String language) {
        return isSpanish(language) ? "Cancelar" : "Cancel";
    }

    public static boolean isSpanish(String language) {
        return language != null && language.toLowerCase(java.util.Locale.ROOT).startsWith("es");
    }

    private static String valueOf(Map<String, Object> args, String path) {
        if (args == null) return "";
        Object v = args.get(path);
        if (v == null && path.contains(".")) {
            Object cursor = args;
            for (String part : path.split("\\.")) {
                if (!(cursor instanceof Map<?, ?> map)) { cursor = null; break; }
                cursor = map.get(part);
            }
            v = cursor;
        }
        if (v == null || v instanceof Map<?, ?> || v instanceof Iterable<?>) return "";
        String s = String.valueOf(v).replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        return s.length() > MAX_VALUE_CHARS ? s.substring(0, MAX_VALUE_CHARS - 1) + "…" : s;
    }
}
