package com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The one normalisation both sides of a keyword agree on
 * (smart-lottery tracker C6/C8): trim, collapse whitespace to single spaces,
 * upper-case, strip accents — so «  ticket  7g4k2», «TICKET 7G4K2» and
 * «Tícket 7G4K2» are the same message.
 */
public final class KeywordTextNormalizer {

    private KeywordTextNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return "";
        String decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return decomposed.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** {@code text} starts with {@code prefix} followed by the end of the text or a space (both normalised). */
    public static boolean startsWithWord(String normalizedText, String normalizedPrefix) {
        if (normalizedText == null || normalizedPrefix == null || normalizedPrefix.isEmpty()) return false;
        if (!normalizedText.startsWith(normalizedPrefix)) return false;
        return normalizedText.length() == normalizedPrefix.length()
                || normalizedText.charAt(normalizedPrefix.length()) == ' ';
    }
}
