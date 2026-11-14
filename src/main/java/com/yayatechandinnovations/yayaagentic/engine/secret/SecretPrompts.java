package com.yayatechandinnovations.yayaagentic.engine.secret;

import com.yayatechandinnovations.yayaagentic.engine.confirm.ConfirmPrompts;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The masked secret prompt ({@code docs/guides/admin-panel-sessions.md} §15):
 * a tool flagged {@code secretInput} answers with {@code _secretPrompt}, the
 * engine holds the call in working memory under {@link #SLOT}, shows a
 * {@code secret_prompt} frame, and runs the same call once more when the user
 * supplies the value.
 *
 * <p>Nothing in this class, in the pending slot or in the frame ever holds the
 * value the user typed: it travels from the inbound message to the one tool
 * request and nowhere else.
 */
public final class SecretPrompts {

    /** Field of a tool result that asks for a secret. Removed before anything else reads the result. */
    public static final String RESULT_KEY = "_secretPrompt";
    /** Working-memory slot of the held call. */
    public static final String SLOT = "pending_secret";
    /** {@code ui_hint} kind of the prompt. */
    public static final String HINT_KIND = "secret_prompt";

    public static final String KIND_SECRET = "secret";
    public static final String KIND_CANCEL = "secret_cancel";

    static final int DEFAULT_EXPIRY_SECONDS = 300;
    static final int MAX_EXPIRY_SECONDS = 3600;
    /** Longest value accepted from a client; a code is far shorter. */
    static final int MAX_VALUE_CHARS = 64;
    /** Bounds of a number that may be a code when the prompt names no length. */
    static final int MIN_TYPED_CHARS = 4;
    static final int MAX_TYPED_CHARS = 16;
    /**
     * How long after a prompt lapsed a message that looks like its code is
     * still kept away from the model: a code typed a moment too late is a
     * code all the same.
     */
    static final int LAPSE_GRACE_SECONDS = 300;
    /** How many times a new code may be asked for along one chain of prompts. */
    public static final int MAX_RESENDS = 3;

    private SecretPrompts() {}

    /** A result value without its {@code _secretPrompt}, and the prompt (null when malformed). */
    public record Split(Map<String, Object> prompt, Object strippedValue) {}

    /** The user's structured answer to a prompt, taken out of the inbound message. */
    public record Answer(boolean cancel, String callId, String value) {}

    /** Null when the value carries no {@code _secretPrompt}. */
    @SuppressWarnings("unchecked")
    public static Split split(Object toolValue) {
        if (!(toolValue instanceof Map<?, ?> map) || !map.containsKey(RESULT_KEY)) return null;
        Map<String, Object> stripped = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (!RESULT_KEY.equals(k)) stripped.put(String.valueOf(k), v);
        });
        Object prompt = map.get(RESULT_KEY);
        return new Split(prompt instanceof Map<?, ?> p ? (Map<String, Object>) p : null, stripped);
    }

    /**
     * The frame the client draws: {@code callId, title, message, format,
     * length, expiresInSeconds, attemptsLeft, canResend}. {@code length} and
     * {@code attemptsLeft} are left out when the tool did not give them.
     */
    public static Map<String, Object> frame(String callId, Map<String, Object> prompt, String language) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("callId", callId);
        frame.put("title", text(prompt.get("title"),
                ConfirmPrompts.isSpanish(language) ? "Código" : "Code"));
        frame.put("message", text(prompt.get("message"),
                ConfirmPrompts.isSpanish(language) ? "Escribe el código." : "Enter the code."));
        frame.put("format", text(prompt.get("format"), "digits").toLowerCase(java.util.Locale.ROOT));
        Integer length = positive(prompt.get("length"));
        if (length != null) frame.put("length", length);
        Integer expires = positive(prompt.get("expiresInSeconds"));
        frame.put("expiresInSeconds", Math.min(expires == null ? DEFAULT_EXPIRY_SECONDS : expires,
                MAX_EXPIRY_SECONDS));
        Integer attempts = nonNegative(prompt.get("attemptsLeft"));
        if (attempts != null) frame.put("attemptsLeft", attempts);
        frame.put("canResend", truthy(prompt.get("canResend")));
        return frame;
    }

    /**
     * What is kept while the prompt waits: the tool, the arguments of the call
     * that just ran, its call id, the format and length a typed answer must
     * have, when it lapses, the turn that raised it, and the frame as shown.
     */
    public static Map<String, Object> pending(String toolId, Map<String, Object> args,
                                              Map<String, Object> frame, String turnId, Instant now) {
        return pending(toolId, args, frame, turnId, now, 0);
    }

    /**
     * As above, for a prompt raised by the answer to an earlier one:
     * {@code resends} is how many times a new code was already asked for
     * along that chain.
     */
    public static Map<String, Object> pending(String toolId, Map<String, Object> args,
                                              Map<String, Object> frame, String turnId, Instant now,
                                              int resends) {
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("toolId", toolId);
        pending.put("args", args == null ? Map.of() : args);
        pending.put("callId", frame.get("callId"));
        pending.put("format", frame.get("format"));
        if (frame.get("length") != null) pending.put("length", frame.get("length"));
        pending.put("expiresAt", now.plusSeconds(((Number) frame.get("expiresInSeconds")).longValue()).toString());
        pending.put("turnId", turnId);
        pending.put("frame", frame);
        if (resends > 0) pending.put("resends", resends);
        return pending;
    }

    /** How many times a new code was asked for along the chain of prompts {@code pending} ends. */
    public static int resends(Map<String, Object> pending) {
        Integer n = pending == null ? null : nonNegative(pending.get("resends"));
        return n == null ? 0 : n;
    }

    /** True for a prompt that lapsed no more than a few minutes ago. */
    public static boolean recentlyLapsed(Map<String, Object> pending, Instant now) {
        Instant expiresAt = expiresAt(pending);
        return expiresAt != null && !now.isBefore(expiresAt)
                && now.isBefore(expiresAt.plusSeconds(LAPSE_GRACE_SECONDS));
    }

    public static boolean lapsed(Map<String, Object> pending, Instant now) {
        Instant expiresAt = expiresAt(pending);
        return expiresAt == null || !now.isBefore(expiresAt);
    }

    /**
     * The frame as a client that reopens the conversation gets it: the one
     * that was streamed, with {@code expiresInSeconds} counted from now and
     * {@code expiresAt} added. Null once the prompt lapsed.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> view(Map<String, Object> pending, Instant now) {
        if (pending == null || lapsed(pending, now) || !(pending.get("frame") instanceof Map<?, ?> frame)) {
            return null;
        }
        Instant expiresAt = expiresAt(pending);
        Map<String, Object> view = new LinkedHashMap<>((Map<String, Object>) frame);
        view.put("expiresInSeconds", Math.max(1, java.time.Duration.between(now, expiresAt).toSeconds()));
        view.put("expiresAt", expiresAt.toString());
        return view;
    }

    /** The structured answer in a selection, or null when the selection is something else. */
    public static Answer answerOf(Map<String, Object> selection) {
        if (selection == null) return null;
        String kind = String.valueOf(selection.get("kind"));
        if (!KIND_SECRET.equals(kind) && !KIND_CANCEL.equals(kind)) return null;
        Object callId = selection.get("callId");
        Object value = selection.get("value");
        return new Answer(KIND_CANCEL.equals(kind), callId == null ? null : String.valueOf(callId),
                value == null ? "" : String.valueOf(value).trim());
    }

    /** A null call id is accepted: only one prompt is ever pending. */
    public static boolean answers(Answer answer, Map<String, Object> pending) {
        return answer.callId() == null || answer.callId().equals(String.valueOf(pending.get("callId")));
    }

    /** A value a client may submit: short, and nothing that could not be a header value. */
    public static boolean acceptable(String value) {
        if (value.length() > MAX_VALUE_CHARS) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }

    /** What a message typed on a text channel is, while a prompt waits. */
    public enum TypedKind {
        /** It holds the code, and only one reading of it. */
        CODE,
        /** It may hold a code, but not one that can be read with certainty. It goes nowhere. */
        SUSPECT,
        /** It cannot hold the code: an ordinary message. */
        NONE
    }

    /** {@code value} is set for {@link TypedKind#CODE} only. */
    public record Typed(TypedKind kind, String value) {
        static final Typed SUSPECT = new Typed(TypedKind.SUSPECT, null);
        static final Typed NONE = new Typed(TypedKind.NONE, null);
    }

    private static final String SEPARATORS = "[\\s\\-\\u2010-\\u2015]";
    private static final java.util.regex.Pattern DIGIT_RUN = java.util.regex.Pattern.compile("\\d+");
    /** Digit runs joined by single spaces or hyphens: «481 516», «481-516». */
    private static final java.util.regex.Pattern DIGIT_GROUP =
            java.util.regex.Pattern.compile("\\d+(?:[ \\t\\-\\u2010-\\u2015]\\d+)+");
    private static final java.util.regex.Pattern WORD = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+");

    /**
     * A message typed on a text channel while {@code pending} waits.
     *
     * <p>The message is the code when, without spaces and hyphens, it has the
     * prompt's format and length; or when exactly one part of it has: «mi
     * código es 481516», «481516.». A prompt that states no length is never
     * answered by a typed message.
     *
     * <p>Anything else that could still hold a code is {@link TypedKind#SUSPECT}:
     * for a numeric code, any message with a digit in it; for other formats,
     * any message at all. Such a message is neither the code nor a message for
     * the model.
     */
    public static Typed readTyped(Map<String, Object> pending, String text) {
        if (pending == null || text == null || text.isBlank()) return Typed.NONE;
        String format = String.valueOf(pending.get("format"));
        Integer length = positive(pending.get("length"));
        if ("digits".equals(format)) {
            String ascii = asciiDigits(text);
            if (!hasDigit(ascii)) return Typed.NONE;
            if (length == null) return Typed.SUSPECT;
            String whole = ascii.replaceAll(SEPARATORS, "");
            if (whole.length() == length && allDigits(whole)) return new Typed(TypedKind.CODE, whole);
            java.util.Set<String> candidates = new java.util.LinkedHashSet<>();
            collect(DIGIT_RUN, ascii, length, candidates);
            collect(DIGIT_GROUP, ascii, length, candidates);
            return candidates.size() == 1 ? new Typed(TypedKind.CODE, candidates.iterator().next())
                    : Typed.SUSPECT;
        }
        if (length == null) return Typed.SUSPECT;
        String whole = text.replaceAll(SEPARATORS, "");
        if ("alphanumeric".equals(format)) {
            if (whole.length() == length && allAlphanumeric(whole)) return new Typed(TypedKind.CODE, whole);
            // Inside a sentence only a word with a digit in it stands out as the code.
            java.util.Set<String> candidates = new java.util.LinkedHashSet<>();
            java.util.regex.Matcher m = WORD.matcher(text);
            while (m.find()) {
                String word = m.group();
                if (word.length() == length && allAlphanumeric(word) && hasDigit(word)) candidates.add(word);
            }
            return candidates.size() == 1 ? new Typed(TypedKind.CODE, candidates.iterator().next())
                    : Typed.SUSPECT;
        }
        // Any other format: one printable word of exactly the stated length.
        boolean ok = whole.length() == length && text.trim().equals(whole)
                && whole.chars().allMatch(c -> c > 0x20 && c < 0x7F);
        return ok ? new Typed(TypedKind.CODE, whole) : Typed.SUSPECT;
    }

    /**
     * True when {@code text} looks like the answer to a prompt that lapsed a
     * moment ago: the code as {@link #readTyped} would read it, or — when the
     * prompt stated no length — a number of 4 to 16 digits.
     */
    public static boolean looksLikeCode(Map<String, Object> pending, String text) {
        if (pending == null || text == null || text.isBlank()) return false;
        if (readTyped(pending, text).kind() == TypedKind.CODE) return true;
        if (positive(pending.get("length")) != null || !"digits".equals(String.valueOf(pending.get("format")))) {
            return false;
        }
        java.util.regex.Matcher m = DIGIT_RUN.matcher(asciiDigits(text).replaceAll(SEPARATORS, ""));
        while (m.find()) {
            int n = m.group().length();
            if (n >= MIN_TYPED_CHARS && n <= MAX_TYPED_CHARS) return true;
        }
        return false;
    }

    /** True when {@code text} has a digit in it, in any script. */
    public static boolean hasDigit(String text) {
        return text != null && text.chars().anyMatch(Character::isDigit);
    }

    private static void collect(java.util.regex.Pattern pattern, String text, int length,
                                java.util.Set<String> into) {
        java.util.regex.Matcher m = pattern.matcher(text);
        while (m.find()) {
            String candidate = m.group().replaceAll(SEPARATORS, "");
            if (candidate.length() == length) into.add(candidate);
        }
    }

    /** Digits of any script as 0-9, so «٤٨١٥١٦» is read like «481516». */
    private static String asciiDigits(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 0x7F && Character.isDigit(c)) {
                if (out == null) out = new StringBuilder(text);
                out.setCharAt(i, (char) ('0' + Character.digit(c, 10)));
            }
        }
        return out == null ? text : out.toString();
    }

    private static boolean allDigits(String s) {
        return !s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static boolean allAlphanumeric(String s) {
        return !s.isEmpty() && s.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'));
    }

    /* ----------------------------- fixed copy ----------------------------- */

    /** What is stored as the user's turn instead of the value. */
    public static String entered(String language) {
        return ConfirmPrompts.isSpanish(language) ? "(código ingresado)" : "(code entered)";
    }

    /** What is stored as the user's turn when a new code was asked for. */
    public static String resendRequested(String language) {
        return ConfirmPrompts.isSpanish(language) ? "(reenviar código)" : "(resend code)";
    }

    public static String stale(String language) {
        return ConfirmPrompts.isSpanish(language)
                ? "Ese código ya no está vigente. Dime de nuevo qué quieres hacer."
                : "That code prompt is no longer pending. Tell me again what you want to do.";
    }

    public static String invalid(String language) {
        return ConfirmPrompts.isSpanish(language)
                ? "Ese código no tiene un formato válido. Escríbelo de nuevo."
                : "That code is not in a valid format. Enter it again.";
    }

    /**
     * The answer to a message that may hold a code but could not be read as
     * the code: the prompt keeps waiting.
     */
    public static String awaiting(Map<String, Object> pending, String language) {
        boolean es = ConfirmPrompts.isSpanish(language);
        Integer length = positive(pending.get("length"));
        if (length == null) {
            return es
                    ? "No puedo leer ese código en un mensaje. Escribe «cancelar» para dejarlo."
                    : "I cannot read that code from a message. Write \"cancel\" to drop it.";
        }
        boolean digits = "digits".equals(String.valueOf(pending.get("format")));
        return es
                ? "Sigo esperando el código de " + length + (digits ? " dígitos" : " caracteres")
                        + ". Envíalo solo, sin más texto, o escribe «cancelar» para dejarlo."
                : "I am still waiting for the " + length + (digits ? "-digit" : "-character")
                        + " code. Send it alone, with no other text, or write \"cancel\" to drop it.";
    }

    public static String resendLimit(String language) {
        return ConfirmPrompts.isSpanish(language)
                ? "Ya pedí un código nuevo varias veces. Escribe el último que recibiste, o cancela y vuelve a intentarlo."
                : "A new code was already requested several times. Enter the last one you received, or cancel and try again.";
    }

    /* ------------------------------ internals ----------------------------- */

    private static Instant expiresAt(Map<String, Object> pending) {
        try {
            return Instant.parse(String.valueOf(pending.get("expiresAt")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(Object raw, String fallback) {
        if (raw == null) return fallback;
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static Integer positive(Object raw) {
        Integer n = nonNegative(raw);
        return n == null || n == 0 ? null : n;
    }

    private static Integer nonNegative(Object raw) {
        if (raw instanceof Number n) return n.intValue() < 0 ? null : n.intValue();
        if (raw instanceof String s) {
            try {
                int v = Integer.parseInt(s.trim());
                return v < 0 ? null : v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean truthy(Object raw) {
        return raw instanceof Boolean b ? b : raw != null && "true".equalsIgnoreCase(String.valueOf(raw).trim());
    }
}
