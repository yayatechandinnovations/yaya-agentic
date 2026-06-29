package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Real Twilio Verify implementation ({@code chat-channel-design.md} §20). Uses
 * Twilio's Verify v2 service (handles code generation, delivery, retries, and
 * Fraud Guard) rather than raw Programmable SMS. Registers only when a Verify
 * service SID is configured ({@code yaya.agentic.chat.verify.twilio-service-sid});
 * otherwise {@link DevNoopPhoneVerifier} stays in place.
 *
 * <p>NOTE: issues live Twilio API calls; cannot be exercised in CI without
 * credentials. The §20.4 toll-fraud rate limits live in the controller, not here.
 * Wired by {@link PhoneVerifierConfig} only when a Verify service SID is set.
 */
public class TwilioVerifyPhoneVerifier implements PhoneVerifier {

    private static final Logger log = LoggerFactory.getLogger(TwilioVerifyPhoneVerifier.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final TwilioProperties props;
    private final WebClient.Builder webClientBuilder;
    private final String serviceSid;
    private final String verifyBaseUrl;

    public TwilioVerifyPhoneVerifier(
            TwilioProperties props,
            WebClient.Builder webClientBuilder,
            String serviceSid,
            String verifyBaseUrl) {
        this.props = props;
        this.webClientBuilder = webClientBuilder;
        this.serviceSid = serviceSid;
        this.verifyBaseUrl = verifyBaseUrl;
    }

    @Override
    public String name() { return "twilio-verify"; }

    @Override
    public StartResult start(String phoneE164) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", phoneE164);
        form.add("Channel", "sms");
        try {
            JsonNode resp = post("/Verifications", form);
            String status = resp.path("status").asText("");
            return "pending".equals(status) ? StartResult.pending()
                    : StartResult.failed("unexpected verify status: " + status);
        } catch (WebClientResponseException e) {
            log.warn("Twilio Verify start failed ({}): {}", e.getStatusCode(), e.getResponseBodyAsString());
            return StartResult.failed("verification could not be sent");
        } catch (RuntimeException e) {
            log.warn("Twilio Verify start error: {}", e.getMessage());
            return StartResult.failed("verification provider error");
        }
    }

    @Override
    public CheckResult check(String phoneE164, String code) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", phoneE164);
        form.add("Code", code);
        try {
            JsonNode resp = post("/VerificationCheck", form);
            return "approved".equals(resp.path("status").asText(""))
                    ? CheckResult.pass() : CheckResult.denied("invalid or expired code");
        } catch (WebClientResponseException e) {
            // Twilio returns 404 on an expired/last-attempt verification.
            return CheckResult.denied("invalid or expired code");
        } catch (RuntimeException e) {
            log.warn("Twilio Verify check error: {}", e.getMessage());
            return CheckResult.denied("verification provider error");
        }
    }

    private JsonNode post(String path, LinkedMultiValueMap<String, String> form) {
        String url = verifyBaseUrl + "/v2/Services/" + serviceSid + path;
        return webClientBuilder.clone().build().post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, basicAuth())
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);
    }

    private String basicAuth() {
        String user = notBlank(props.apiKeySid()) ? props.apiKeySid() : props.accountSid();
        String secret = notBlank(props.apiKeySid()) ? props.apiKeySecret() : props.authToken();
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
