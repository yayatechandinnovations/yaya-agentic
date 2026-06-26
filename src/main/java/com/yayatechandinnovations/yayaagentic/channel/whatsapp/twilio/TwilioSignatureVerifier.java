package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Twilio's standard webhook signature scheme. See
 * <a href="https://www.twilio.com/docs/usage/webhooks/webhooks-security">Twilio
 * webhook security docs</a> — short version:
 *
 * <ol>
 *   <li>Take the full callback URL as Twilio invoked it.</li>
 *   <li>Append each {@code application/x-www-form-urlencoded} body param
 *       (key + value, concatenated, no delimiter) sorted alphabetically by
 *       key.</li>
 *   <li>HMAC-SHA1 the result with the account's Auth Token as the key.</li>
 *   <li>Base64-encode the HMAC; compare to the {@code X-Twilio-Signature}
 *       header in constant time.</li>
 * </ol>
 *
 * <p>The verifier accepts the URL fully qualified — callers MUST resolve it
 * from the platform's public base URL, not from incoming {@code Host}
 * headers, because a malicious proxy can otherwise force a signature match
 * by manipulating the perceived URL. See §16's "Inbound signature
 * verification is non-bypassable" non-negotiable.
 */
public final class TwilioSignatureVerifier {

    private static final String HMAC_SHA1 = "HmacSHA1";

    private TwilioSignatureVerifier() {}

    /**
     * @param url             full canonical URL Twilio called (the platform
     *                        builds this from
     *                        {@code yaya.agentic.channels.twilio.webhook-base-url}
     *                        + the request path + sender-id segment)
     * @param formParams      decoded form params (the order they appear in
     *                        the request body doesn't matter — we sort)
     * @param authToken       Twilio account Auth Token
     * @param presentedSig    {@code X-Twilio-Signature} header value
     * @return true iff {@code presentedSig} matches the expected signature
     *         in constant time
     */
    public static boolean verify(String url,
                                 Map<String, String> formParams,
                                 String authToken,
                                 String presentedSig) {
        if (authToken == null || authToken.isBlank()) return false;
        if (presentedSig == null || presentedSig.isBlank()) return false;

        String expected = computeExpectedSignature(url, formParams, authToken);
        return constantTimeEquals(expected, presentedSig);
    }

    /** Compute the signature WE'd compute given the inputs — useful for
     *  test fixtures that need to forge a valid signature against a stubbed
     *  webhook. */
    public static String computeExpectedSignature(String url,
                                                  Map<String, String> formParams,
                                                  String authToken) {
        StringBuilder source = new StringBuilder(url == null ? "" : url);
        if (formParams != null && !formParams.isEmpty()) {
            List<String> keys = new ArrayList<>(formParams.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                source.append(key);
                source.append(formParams.get(key) == null ? "" : formParams.get(key));
            }
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(
                    authToken.getBytes(StandardCharsets.UTF_8), HMAC_SHA1));
            byte[] hmac = mac.doFinal(source.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 unavailable on this JVM", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }
}
