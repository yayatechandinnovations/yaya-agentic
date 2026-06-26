package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Truth-table coverage of the Twilio signature verifier. Tests rely on the
 * verifier's {@code computeExpectedSignature} as the source of truth (Twilio's
 * own python SDK uses the identical algorithm) — so a successful sign-then-
 * verify pass is the canary that the algorithm is correct.
 */
class TwilioSignatureVerifierTest {

    private static final String URL = "https://hooks.example/v1/channels/whatsapp/twilio/webhook/snd_acme";
    private static final String AUTH_TOKEN = "twilio-auth-token-12345";

    private Map<String, String> sampleParams() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("MessageSid", "SMabcdef");
        p.put("From", "whatsapp:+15551234567");
        p.put("To", "whatsapp:+15559998888");
        p.put("Body", "Hello there");
        return p;
    }

    @Test
    void valid_signature_passes() {
        String sig = TwilioSignatureVerifier.computeExpectedSignature(URL, sampleParams(), AUTH_TOKEN);
        assertThat(TwilioSignatureVerifier.verify(URL, sampleParams(), AUTH_TOKEN, sig)).isTrue();
    }

    @Test
    void wrong_token_fails() {
        String sig = TwilioSignatureVerifier.computeExpectedSignature(URL, sampleParams(), AUTH_TOKEN);
        assertThat(TwilioSignatureVerifier.verify(URL, sampleParams(),
                "wrong-token", sig)).isFalse();
    }

    @Test
    void wrong_url_fails() {
        String sig = TwilioSignatureVerifier.computeExpectedSignature(URL, sampleParams(), AUTH_TOKEN);
        assertThat(TwilioSignatureVerifier.verify(URL + "?attacker=1",
                sampleParams(), AUTH_TOKEN, sig)).isFalse();
    }

    @Test
    void tampered_body_fails() {
        String sig = TwilioSignatureVerifier.computeExpectedSignature(URL, sampleParams(), AUTH_TOKEN);
        Map<String, String> tampered = new LinkedHashMap<>(sampleParams());
        tampered.put("Body", "Different body");
        assertThat(TwilioSignatureVerifier.verify(URL, tampered, AUTH_TOKEN, sig)).isFalse();
    }

    @Test
    void missing_signature_fails() {
        assertThat(TwilioSignatureVerifier.verify(URL, sampleParams(), AUTH_TOKEN, null)).isFalse();
        assertThat(TwilioSignatureVerifier.verify(URL, sampleParams(), AUTH_TOKEN, "")).isFalse();
    }

    @Test
    void missing_token_fails() {
        String sig = TwilioSignatureVerifier.computeExpectedSignature(URL, sampleParams(), AUTH_TOKEN);
        assertThat(TwilioSignatureVerifier.verify(URL, sampleParams(), null, sig)).isFalse();
        assertThat(TwilioSignatureVerifier.verify(URL, sampleParams(), "", sig)).isFalse();
    }

    @Test
    void param_ordering_does_not_matter() {
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("Body", "Hi");
        ordered.put("From", "whatsapp:+1");
        ordered.put("MessageSid", "SM1");

        Map<String, String> reordered = new LinkedHashMap<>();
        reordered.put("MessageSid", "SM1");
        reordered.put("From", "whatsapp:+1");
        reordered.put("Body", "Hi");

        String sigA = TwilioSignatureVerifier.computeExpectedSignature(URL, ordered, AUTH_TOKEN);
        String sigB = TwilioSignatureVerifier.computeExpectedSignature(URL, reordered, AUTH_TOKEN);
        assertThat(sigA).isEqualTo(sigB);
    }

    @Test
    void empty_params_still_signs_url() {
        String sig = TwilioSignatureVerifier.computeExpectedSignature(URL, Map.of(), AUTH_TOKEN);
        assertThat(sig).isNotBlank();
        assertThat(TwilioSignatureVerifier.verify(URL, Map.of(), AUTH_TOKEN, sig)).isTrue();
    }
}
