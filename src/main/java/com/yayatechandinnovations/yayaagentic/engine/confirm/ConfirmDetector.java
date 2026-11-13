package com.yayatechandinnovations.yayaagentic.engine.confirm;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Heuristic confirmation classifier for a pending confirm frame. Recognises
 * whole-message affirmatives/negatives AND phrases led by one ("sí, anúlalo",
 * "dale que sí", "no, déjalo"), the frame's own button labels, and the
 * {@code 1}/{@code 2} numbered fallback. Anything else is
 * {@link Signal#UNCLEAR} so the engine can treat the message as a pivot.
 *
 * <p>An exact-match-only detector dropped the pending frame on every natural
 * reply, the model re-proposed the call, and the user was asked again — the
 * restart loop behind the "asked three times" defect
 * (whatsapp-ops-integration-design.md §3, rule R3).
 */
@Component
public class ConfirmDetector {

    public enum Signal { CONFIRM, CANCEL, UNCLEAR }

    /** Whole-message affirmatives, accent-folded and punctuation-stripped. */
    private static final Set<String> AFFIRMATIVE = Set.of(
            "yes", "yeah", "yep", "yup", "ok", "okay", "sure",
            "confirm", "confirmed", "go", "go ahead", "do it", "please do",
            "y", "👍",
            // Spanish — DR banca / ops chat: "Sí" MUST resolve a pending confirm.
            "si", "claro", "dale", "confirmar", "confirmo", "confirmado", "hazlo",
            "de acuerdo", "esta bien", "vale", "listo", "perfecto", "correcto",
            "procede", "adelante", "aprobar", "apruebo", "apruebala", "apruebalo",
            "anulalo", "anulala", "hazlo ya", "si confirmo", "si claro", "claro que si",
            "dale que si", "si por favor");

    private static final Set<String> NEGATIVE = Set.of(
            "no", "nope", "nah", "cancel", "stop", "wait", "nevermind",
            "never mind", "don't", "dont", "do not", "abort", "n", "👎",
            "cancela", "cancelar", "cancelalo", "espera", "para", "detente",
            "olvidalo", "dejalo", "mejor no", "no gracias", "todavia no", "aun no");

    /** A message LED by one of these is affirmative unless a negation follows. */
    private static final Set<String> AFFIRMATIVE_LEADS = Set.of(
            "si", "yes", "ok", "okay", "dale", "claro", "confirmo", "confirmar", "confirmado",
            "hazlo", "procede", "adelante", "listo", "perfecto", "correcto", "vale",
            "aprobar", "apruebo", "apruebala", "apruebalo", "anulalo", "anulala", "sure", "yep");

    /** A message LED by one of these is negative unless an affirmative follows. */
    private static final Set<String> NEGATIVE_LEADS = Set.of(
            "no", "nope", "cancela", "cancelar", "cancelalo", "espera", "detente",
            "olvidalo", "dejalo", "stop", "cancel", "wait", "abort");

    /** Any of these inside an affirmative-led phrase makes it ambiguous. */
    private static final Set<String> NEGATION_TOKENS = Set.of(
            "no", "nunca", "cancela", "cancelar", "cancelalo", "espera", "todavia", "aun",
            "pero", "not", "dont", "wait");

    /** Negative-led phrases that are doubt, not refusal ("no sé"). */
    private static final List<String> DOUBT_PHRASES = List.of(
            "no se", "no estoy seguro", "no estoy segura", "no entiendo", "not sure");

    /** Negative-led phrases whose tail re-affirms ("no, sí hazlo") — ambiguous. */
    private static final Set<String> AFFIRMATION_TOKENS = Set.of(
            "si", "hazlo", "dale", "confirmo", "procede", "adelante", "yes");

    public Signal detect(String text) {
        return detect(text, List.of());
    }

    /**
     * @param frameLabels the pending frame's own button labels, in order
     *                    (confirm label first, cancel label second); a reply
     *                    that is exactly one of them resolves the frame
     */
    public Signal detect(String text, Collection<String> frameLabels) {
        if (text == null) return Signal.UNCLEAR;
        String raw = text.trim();
        if (raw.isEmpty()) return Signal.UNCLEAR;
        if (raw.startsWith("👍")) return Signal.CONFIRM;
        if (raw.startsWith("👎")) return Signal.CANCEL;

        String normalized = normalize(raw);
        if (normalized.isEmpty()) return Signal.UNCLEAR;

        if (frameLabels != null && !frameLabels.isEmpty()) {
            List<String> labels = frameLabels.stream().map(ConfirmDetector::normalize).toList();
            if (!labels.isEmpty() && labels.get(0).equals(normalized)) return Signal.CONFIRM;
            if (labels.size() > 1 && labels.get(1).equals(normalized)) return Signal.CANCEL;
        }
        // Numbered fallback for text-only channels: "1. Confirmar / 2. Cancelar".
        if ("1".equals(normalized)) return Signal.CONFIRM;
        if ("2".equals(normalized)) return Signal.CANCEL;

        if (AFFIRMATIVE.contains(normalized)) return Signal.CONFIRM;
        if (NEGATIVE.contains(normalized)) return Signal.CANCEL;

        List<String> tokens = Arrays.asList(normalized.split(" "));
        String lead = tokens.get(0);
        if (AFFIRMATIVE_LEADS.contains(lead)) {
            for (int i = 1; i < tokens.size(); i++) {
                if (NEGATION_TOKENS.contains(tokens.get(i))) return Signal.UNCLEAR;
            }
            return Signal.CONFIRM;
        }
        if (NEGATIVE_LEADS.contains(lead)) {
            for (String doubt : DOUBT_PHRASES) {
                if (normalized.equals(doubt) || normalized.startsWith(doubt + " ")) return Signal.UNCLEAR;
            }
            for (int i = 1; i < tokens.size(); i++) {
                if (AFFIRMATION_TOKENS.contains(tokens.get(i))) return Signal.UNCLEAR;
            }
            return Signal.CANCEL;
        }
        return Signal.UNCLEAR;
    }

    public Optional<Signal> classify(String text) {
        Signal s = detect(text);
        return s == Signal.UNCLEAR ? Optional.empty() : Optional.of(s);
    }

    /** Lower-case, fold accents, turn punctuation into spaces, collapse runs. */
    public static String normalize(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String folded = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return folded.replaceAll("[^\\p{L}\\p{N}' ]+", " ")
                .replace("'", "")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
