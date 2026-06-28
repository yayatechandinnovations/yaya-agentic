package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * Computes how many billed SMS segments a message body will use, and in which
 * encoding ({@code sms-channel-design.md} §5.1). The BSP splits the wire body
 * itself, so this does NOT split the string — it only reports the count, which
 * the dispatcher records on {@code outbound_messages} for billing and which an
 * eval warns on when a profile habitually emits long replies.
 *
 * <p>GSM-7 fits 160 chars in one segment / 153 per concatenated segment; UCS-2
 * (forced by any non-GSM-7 char — most Spanish accents, emoji) fits 70 / 67.
 * GSM-7 extension chars ({@code ^ { } [ ] ~ | \ €}) cost two septets.
 */
public final class SmsSegmenter {

    private SmsSegmenter() {}

    public enum Encoding { GSM_7, UCS_2 }

    public record Segmentation(Encoding encoding, int segmentCount, int unitCount) {}

    // GSM 7-bit default alphabet (basic set). Note ñ/Ñ ARE here; á/í/ó/ú are NOT.
    private static final String GSM7_BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    // Extension chars: each costs two septets (an escape + the char).
    private static final String GSM7_EXTENSION = "^{}\\[~]|€";

    public static Segmentation analyze(String body) {
        String text = body == null ? "" : body;

        int septets = 0;
        boolean gsm7 = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (GSM7_BASIC.indexOf(c) >= 0) {
                septets += 1;
            } else if (GSM7_EXTENSION.indexOf(c) >= 0) {
                septets += 2;
            } else {
                gsm7 = false;
                break;
            }
        }

        if (gsm7) {
            int count = septets == 0 ? 1 : (septets <= 160 ? 1 : ceilDiv(septets, 153));
            return new Segmentation(Encoding.GSM_7, count, septets);
        }

        // UCS-2: count UTF-16 code units; emoji (surrogate pairs) cost two.
        int units = text.length();
        int count = units == 0 ? 1 : (units <= 70 ? 1 : ceilDiv(units, 67));
        return new Segmentation(Encoding.UCS_2, count, units);
    }

    /** Convenience: just the billed segment count. */
    public static int segmentCount(String body) {
        return analyze(body).segmentCount();
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
