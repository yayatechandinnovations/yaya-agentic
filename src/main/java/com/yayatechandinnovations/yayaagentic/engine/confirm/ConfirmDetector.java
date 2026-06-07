package com.yayatechandinnovations.yayaagentic.engine.confirm;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * M2-D heuristic confirmation classifier. Recognises a small set of
 * affirmative / negative phrasings; anything else is {@link Signal#UNCLEAR}
 * so the engine can drop the pending confirmation and treat the message as
 * a fresh intent (a pivot away from the confirm prompt).
 * <p>
 * Real LLM-assisted intent classification lives behind the same shape and
 * lands in a later M2 sub-phase.
 */
@Component
public class ConfirmDetector {

    public enum Signal { CONFIRM, CANCEL, UNCLEAR }

    private static final Set<String> AFFIRMATIVE = Set.of(
            "yes", "yeah", "yep", "yup", "ok", "okay", "sure",
            "confirm", "confirmed", "go", "go ahead", "do it", "please do",
            "y", "👍");

    private static final Set<String> NEGATIVE = Set.of(
            "no", "nope", "nah", "cancel", "stop", "wait", "nevermind",
            "never mind", "don't", "do not", "abort", "n", "👎");

    public Signal detect(String text) {
        if (text == null) return Signal.UNCLEAR;
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return Signal.UNCLEAR;
        // Strip trailing punctuation that won't change meaning.
        while (!normalized.isEmpty()
                && ".!?,;:".indexOf(normalized.charAt(normalized.length() - 1)) >= 0) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (AFFIRMATIVE.contains(normalized)) return Signal.CONFIRM;
        if (NEGATIVE.contains(normalized)) return Signal.CANCEL;
        return Signal.UNCLEAR;
    }

    public Optional<Signal> classify(String text) {
        Signal s = detect(text);
        return s == Signal.UNCLEAR ? Optional.empty() : Optional.of(s);
    }
}
