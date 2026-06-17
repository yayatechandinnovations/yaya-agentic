package com.yayatechandinnovations.yayaagentic.engine.confirm;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Heuristic classifier for the user's reply to an
 * {@code OnConflict.ASK_USER} prompt. Returns one of four signals:
 * {@link Signal#CONTINUE_EXISTING}, {@link Signal#REPLACE},
 * {@link Signal#VIEW_EXISTING}, {@link Signal#UNCLEAR}.
 *
 * <p>Mirrors the shape of {@link ConfirmDetector}. Phrase-based matching
 * with English + Spanish coverage is the v1 default — real LLM-assisted
 * intent classification can swap in behind the same SPI later without
 * touching the engine wiring.</p>
 */
@Component
public class EntityConflictDetector {

    public enum Signal { CONTINUE_EXISTING, REPLACE, VIEW_EXISTING, UNCLEAR }

    private static final List<String> CONTINUE_PHRASES = List.of(
            "keep", "continue", "stay", "existing", "the current", "the same",
            "don't cancel", "do not cancel", "leave it",
            // es
            "continuar", "continuemos", "sigamos", "seguir", "mantener",
            "mantenlo", "déjalo", "dejalo", "no canceles", "no cancelar",
            "con el actual", "con el existente", "con la actual", "con la existente"
    );

    private static final List<String> REPLACE_PHRASES = List.of(
            "replace", "cancel", "start over", "start fresh", "new one",
            "scrap", "discard", "drop the old", "drop the previous",
            // es
            "reemplazar", "reemplaza", "cancela", "cancelar", "cancelalo",
            "cancélalo", "empezar de nuevo", "empezar de cero", "de cero",
            "anular", "anula", "anular el anterior", "cancela el anterior",
            "borra el anterior", "borrar el anterior"
    );

    private static final List<String> VIEW_PHRASES = List.of(
            "view", "show", "see it", "see the current", "what do i have",
            "what's in it", "what is in it", "show me",
            // es
            "ver", "muéstrame", "muestrame", "muéstralo", "muestralo",
            "ver el actual", "qué tengo", "que tengo", "qué hay", "que hay",
            "ver lo que tengo"
    );

    public Signal detect(String text) {
        if (text == null) return Signal.UNCLEAR;
        String n = text.toLowerCase(Locale.ROOT).strip();
        if (n.isEmpty()) return Signal.UNCLEAR;
        // Order matters: explicit "view" wins over "show me what I have…
        // then cancel it", though in practice the engine re-asks after a
        // VIEW so the user gets to pick again.
        if (containsAny(n, VIEW_PHRASES)) return Signal.VIEW_EXISTING;
        if (containsAny(n, REPLACE_PHRASES)) return Signal.REPLACE;
        if (containsAny(n, CONTINUE_PHRASES)) return Signal.CONTINUE_EXISTING;
        return Signal.UNCLEAR;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}
