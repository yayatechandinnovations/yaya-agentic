package com.yayatechandinnovations.yayaagentic.engine.confirm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reads a typed reply to a pending plan (admin-panel-sessions.md §16). A plan
 * is a numbered list of changes, so the reading of a single confirmation
 * ({@link ConfirmDetector}: «1» is yes, any message led by «sí» is yes) would
 * run every step on «1» or «sí, solo la 1». Here a reply runs the whole plan
 * only when the whole message is a yes; it runs some steps only when it lists
 * their numbers and nothing else; and whatever looks like an answer but says
 * neither is {@link Kind#ASK}: nothing runs and the person is asked how to
 * choose. No reading ever adds a step the reply did not name.
 */
public final class PlanAnswers {

    public enum Kind {
        /** Every step. */
        ALL,
        /** The listed steps. */
        SOME,
        /** Nothing. */
        NONE,
        /** An answer to the plan that does not say which steps: ask, run nothing. */
        ASK,
        /** Not an answer to the plan. */
        OTHER
    }

    /** @param stepIds for {@link Kind#SOME}, the steps to run, in the plan's order */
    public record Answer(Kind kind, List<String> stepIds) {
        static Answer of(Kind kind) {
            return new Answer(kind, List.of());
        }
    }

    /** The words of {@link ConfirmPrompts#planReplyHint}, and what means the same. */
    private static final Set<String> ALL_WORDS = Set.of(
            "todo", "todos", "todas", "hacer todo", "hazlo todo", "hazlos todos", "hazlas todas",
            "all", "do all", "all of them", "everything");
    private static final Set<String> NONE_WORDS = Set.of("ninguno", "ninguna", "nada", "none", "nothing");

    /** Words a whole-plan yes may add without saying anything else. */
    private static final Set<String> YES_PADDING = Set.of(
            "por", "favor", "gracias", "que", "ya", "ahora", "pues", "todo", "todos", "todas", "please", "all", "now");

    /** Words that may stand around step numbers without changing which steps are meant. */
    private static final Set<String> LIST_WORDS = Set.of(
            "solo", "solamente", "unicamente", "nada", "mas", "la", "las", "el", "los", "paso", "pasos",
            "numero", "numeros", "y", "e", "haz", "hazme", "quiero", "por", "favor",
            "only", "just", "step", "steps", "number", "numbers", "and", "the", "do", "please");
    private static final Set<String> EVERY_WORDS = Set.of("todo", "todos", "todas", "all", "everything");
    private static final Set<String> EXCEPT_WORDS = Set.of("menos", "excepto", "salvo", "sin", "except", "but");

    /** «del 1 al 3»: about the steps, but a range is not a list. */
    private static final Set<String> RANGE_WORDS = Set.of("del", "de", "desde", "al", "a", "hasta", "from", "to");

    /** A reply that opens with one of these is about the plan's steps. */
    private static final Set<String> CHOOSING_LEADS = Set.of(
            "solo", "solamente", "unicamente", "only", "just",
            "todo", "todos", "todas", "all", "everything",
            "menos", "excepto", "salvo", "sin", "except",
            "ninguno", "ninguna", "none");

    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}");
    /** «1-3», «1 a 3»: a range is not a list. */
    private static final Pattern RANGE = Pattern.compile("\\d\\s*(?:[-–—]|\\ba\\b|\\bal\\b|\\bto\\b)\\s*\\d");

    private PlanAnswers() {}

    /**
     * @param text         the typed reply
     * @param frameLabels  the plan's own labels (confirm first, cancel second)
     * @param stepCount    how many steps the plan has; their ids are {@code 1..stepCount}
     * @param askedAlready the person was already asked for step numbers, so a
     *                     bare number is one
     */
    public static Answer read(String text, Collection<String> frameLabels, int stepCount, boolean askedAlready) {
        if (text == null || text.isBlank()) return Answer.of(Kind.OTHER);
        String raw = text.trim();
        String normalized = ConfirmDetector.normalize(raw);
        if (normalized.isEmpty()) {
            // Nothing but symbols: only the two thumbs say something.
            if (raw.startsWith("👍")) return Answer.of(Kind.ALL);
            if (raw.startsWith("👎")) return Answer.of(Kind.NONE);
            return Answer.of(Kind.OTHER);
        }

        // ---- the whole message is a yes or a no ----
        List<String> labels = frameLabels == null ? List.of()
                : frameLabels.stream().map(ConfirmDetector::normalize).toList();
        if (!labels.isEmpty() && labels.get(0).equals(normalized)) return Answer.of(Kind.ALL);
        if (labels.size() > 1 && labels.get(1).equals(normalized)) return Answer.of(Kind.NONE);
        if (ConfirmDetector.AFFIRMATIVE.contains(normalized) || ALL_WORDS.contains(normalized)) {
            return Answer.of(Kind.ALL);
        }
        if (ConfirmDetector.NEGATIVE.contains(normalized) || NONE_WORDS.contains(normalized)) {
            return Answer.of(Kind.NONE);
        }
        List<String> tokens = List.of(normalized.split(" "));
        boolean yesLed = ConfirmDetector.AFFIRMATIVE_LEADS.contains(tokens.get(0));
        if (yesLed && tokens.stream().allMatch(t ->
                ConfirmDetector.AFFIRMATIVE_LEADS.contains(t) || YES_PADDING.contains(t))) {
            // «sí, dale», «ok perfecto», «sí, todo por favor»: nothing but yes.
            return Answer.of(Kind.ALL);
        }

        // ---- step numbers and nothing else ----
        List<String> listed = stepList(tokens, stepCount, RANGE.matcher(raw).find());
        if (listed != null) {
            boolean bareNumber = tokens.size() == 1;
            // «2» alone is «Cancelar» to whoever learned the numbered options
            // of a single confirmation: it names a step only once asked for one.
            if (bareNumber && !askedAlready) return Answer.of(Kind.ASK);
            return listed.isEmpty() ? Answer.of(Kind.ASK) : new Answer(Kind.SOME, listed);
        }

        // ---- an answer that does not say which steps ----
        boolean hasNumber = tokens.stream().anyMatch(t -> NUMBER.matcher(t).matches());
        boolean choosing = CHOOSING_LEADS.contains(tokens.get(0))
                || tokens.stream().allMatch(t -> isListToken(t) || EVERY_WORDS.contains(t)
                        || EXCEPT_WORDS.contains(t) || RANGE_WORDS.contains(t));
        if (yesLed || choosing) return Answer.of(Kind.ASK);
        if (ConfirmDetector.NEGATIVE_LEADS.contains(tokens.get(0))) {
            // «no, solo la 1» chooses; «no, déjalo» refuses; «no sé» is neither.
            if (hasNumber || tokens.stream().anyMatch(t -> CHOOSING_LEADS.contains(t))) return Answer.of(Kind.ASK);
            return new ConfirmDetector().detect(raw) == ConfirmDetector.Signal.CANCEL
                    ? Answer.of(Kind.NONE) : Answer.of(Kind.ASK);
        }
        return Answer.of(Kind.OTHER);
    }

    private static boolean isListToken(String token) {
        return LIST_WORDS.contains(token) || NUMBER.matcher(token).matches();
    }

    /**
     * The steps a reply lists, in the plan's order: «solo la 1 y la 3», «sí,
     * pasos 2 y 3», «todo menos la 2». Null when the reply is anything more
     * than a list; empty when it is a list that names no step of this plan
     * or leaves none.
     */
    private static List<String> stepList(List<String> tokens, int stepCount, boolean range) {
        int from = 0;
        while (from < tokens.size() && ConfirmDetector.AFFIRMATIVE_LEADS.contains(tokens.get(from))) from++;
        boolean except = false;
        TreeSet<Integer> named = new TreeSet<>();
        for (int i = from; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (NUMBER.matcher(token).matches()) {
                named.add(Integer.parseInt(token));
            } else if (EXCEPT_WORDS.contains(token)) {
                // «menos» comes before every number it leaves out, once.
                if (except || !named.isEmpty()) return null;
                except = true;
            } else if (EVERY_WORDS.contains(token)) {
                // «todo» only as the start of «todo menos …».
                if (except || !named.isEmpty()) return null;
            } else if (!LIST_WORDS.contains(token)) {
                return null;
            }
        }
        if (named.isEmpty()) return null;
        // A number the plan does not have, or a range: not guessed at.
        if (range || named.first() < 1 || named.last() > stepCount) return List.of();
        List<String> ids = new ArrayList<>();
        for (int step = 1; step <= stepCount; step++) {
            if (named.contains(step) != except) ids.add(String.valueOf(step));
        }
        return ids;
    }
}
