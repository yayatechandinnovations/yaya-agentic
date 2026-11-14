package com.yayatechandinnovations.yayaagentic.engine.confirm;

import java.util.List;
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
 *
 * <p>A plan (admin-panel-sessions.md §16) is worded from the same prompts:
 * each step is its tool's question turned into a statement.
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

    // ---- plans (admin-panel-sessions.md §16) --------------------------------

    public static String planTitle(String language) {
        return isSpanish(language) ? "Voy a hacer esto, en orden:" : "I will do this, in order:";
    }

    public static String planConfirmLabel(String language) {
        return isSpanish(language) ? "Hacer todo" : "Do all";
    }

    public static String planQuestion(String language) {
        return isSpanish(language) ? "¿Lo hago?" : "Shall I?";
    }

    /**
     * One step's line: the tool's question for this call without one leading
     * «¿» and one trailing «?». A tool with no question is named by its id.
     */
    public static String stepSummary(String template, Map<String, Object> args, String toolId, String language) {
        if (template == null || template.isBlank()) return toolId;
        String line = render(template, args, language);
        if (line.startsWith("¿")) line = line.substring(1);
        if (line.endsWith("?")) line = line.substring(0, line.length() - 1);
        line = line.trim();
        return line.isEmpty() ? toolId : line;
    }

    /** The plan as text, for a channel that draws no card. */
    public static String planText(List<String> stepSummaries, String language) {
        StringBuilder out = new StringBuilder(planTitle(language));
        for (int i = 0; i < stepSummaries.size(); i++) {
            out.append('\n').append(i + 1).append(". ").append(stepSummaries.get(i));
        }
        return out.append('\n').append(planQuestion(language)).toString();
    }

    /**
     * How to answer a plan by typing, for a channel that shows no buttons.
     * Words, never numbered options: the steps above it are the numbered
     * list. {@link PlanAnswers} reads exactly these answers.
     */
    public static String planReplyHint(String language) {
        return isSpanish(language)
                ? "Responde TODO para hacerlos todos, CANCELAR para no hacer nada, "
                        + "o dime cuáles pasos quieres (por ejemplo «solo la 1»)."
                : "Reply ALL to do them all, CANCEL to do nothing, "
                        + "or tell me which steps you want (for example \"only 1\").";
    }

    /** {@link #planReplyHint} in the language the plan's own frame was written in. */
    public static String planReplyHintFor(Map<String, Object> planFrame) {
        Object label = planFrame == null ? null : planFrame.get("confirmLabel");
        return planReplyHint(planConfirmLabel("en").equals(label) ? "en" : "es");
    }

    /**
     * A typed reply to a plan that is neither a whole yes, nor a no, nor a
     * list of step numbers: nothing runs and the person is told how to choose.
     *
     * @param buttons the person is looking at the plan's card (an app)
     */
    public static String planAsk(String language, boolean buttons, String confirmLabel) {
        boolean es = isSpanish(language);
        String lead = es ? "No quiero hacer de más. " : "I do not want to do more than you meant. ";
        if (!buttons) return lead + planReplyHint(language);
        return lead + (es
                ? "Toca «" + confirmLabel + "», o dime cuáles pasos quieres (por ejemplo «solo la 1»)."
                : "Tap \"" + confirmLabel + "\", or tell me which steps you want (for example \"only 1\").");
    }

    /** A cancel that arrived after the plan's run had been agreed to. */
    public static String planUnderWay(String language) {
        return isSpanish(language)
                ? "Ya estaba en marcha, no alcancé a cancelarlo."
                : "It was already under way, so I could not cancel it.";
    }

    /** What a confirmable call is told when another one of the same answer took the card. */
    public static String deferred(String language) {
        return isSpanish(language)
                ? "Este cambio se confirma aparte, después."
                : "This change is confirmed separately, afterwards.";
    }

    /** Another message arrived while the plan's steps are still running. */
    public static String planBusy(String language) {
        return isSpanish(language)
                ? "Todavía estoy haciendo esos cambios. Dame un momento."
                : "I am still making those changes. Give me a moment.";
    }

    /**
     * A run that never finished (the platform restarted in the middle), told
     * from what was stored: the steps by number, in four groups.
     */
    public static String planInterrupted(List<String> done, List<String> failed, List<String> unknown,
                                         List<String> notDone, String language) {
        boolean es = isSpanish(language);
        StringBuilder out = new StringBuilder(es ? "El plan se interrumpió." : "The plan was interrupted.");
        appendGroup(out, es ? "Hecho" : "Done", done, es);
        appendGroup(out, es ? "Falló" : "Failed", failed, es);
        appendGroup(out, es ? "Sin confirmar" : "Not confirmed", unknown, es);
        appendGroup(out, es ? "Sin hacer" : "Not done", notDone, es);
        return out.toString();
    }

    private static void appendGroup(StringBuilder out, String label, List<String> ids, boolean es) {
        if (ids == null || ids.isEmpty()) return;
        out.append(' ').append(label).append(": ");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) out.append(i == ids.size() - 1 ? (es ? " y " : " and ") : ", ");
            out.append(ids.get(i));
        }
        out.append('.');
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
