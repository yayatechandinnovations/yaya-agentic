package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.TwilioSubaccountProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Twilio-backed {@link TwilioSubaccountProvisioner}: creates one dedicated
 * subaccount per customer WABA under the platform's parent account via
 * {@code POST /2010-04-01/Accounts.json}. Authenticates with the parent's
 * API key pair (falls back to accountSid:authToken like
 * {@code TwilioA2pRegistrar}).
 *
 * <p>Bean name is load-bearing: {@code DevNoopSubaccountProvisioner} backs
 * off via {@code @ConditionalOnMissingBean(name="twilioSubaccountProvisioner")}.
 */
@Component("twilioSubaccountProvisioner")
@ConditionalOnProperty(name = "yaya.agentic.channels.twilio.account-sid")
public class TwilioSubaccountProvisionerClient implements TwilioSubaccountProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TwilioSubaccountProvisionerClient.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    private final TwilioProperties props;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper json;

    public TwilioSubaccountProvisionerClient(TwilioProperties props,
                                             WebClient.Builder webClientBuilder,
                                             ObjectMapper json) {
        this.props = props;
        this.webClientBuilder = webClientBuilder;
        this.json = json;
    }

    @Override
    public boolean isReady() {
        return props.isConfigured();
    }

    @Override
    public SubaccountProvisionResult provision(String friendlyName) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("FriendlyName", friendlyName);
        try {
            JsonNode resp = webClientBuilder.clone().build().post()
                    .uri(props.effectiveApiBaseUrl() + "/2010-04-01/Accounts.json")
                    .header(HttpHeaders.AUTHORIZATION, parentBasicAuth())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(HTTP_TIMEOUT);
            String sid = text(resp, "sid");
            String authToken = text(resp, "auth_token");
            if (sid == null || authToken == null) {
                return new SubaccountProvisionResult.TransientFailure(
                        "twilio_bad_response",
                        "subaccount create response missing sid or auth_token");
            }
            log.info("Twilio subaccount created ({}…{}) friendlyName={}",
                    sid.substring(0, Math.min(4, sid.length())),
                    sid.substring(Math.max(0, sid.length() - 4)),
                    friendlyName);
            return new SubaccountProvisionResult.Created(sid, authToken);
        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.is5xxServerError() || status.value() == 429 || status.value() == 408) {
                return new SubaccountProvisionResult.TransientFailure(
                        "transient_http_" + status.value(), e.getResponseBodyAsString());
            }
            String code = "http_" + status.value();
            String message = e.getResponseBodyAsString();
            try {
                JsonNode err = json.readTree(message);
                if (err.hasNonNull("code")) code = "twilio_" + err.get("code").asText();
                if (err.hasNonNull("message")) message = err.get("message").asText();
            } catch (Exception ignored) {}
            return new SubaccountProvisionResult.Rejected(code, message);
        } catch (Exception e) {
            log.warn("Twilio subaccount create transport failure: {}", e.getMessage());
            return new SubaccountProvisionResult.TransientFailure("transport_error", e.getMessage());
        }
    }

    private String parentBasicAuth() {
        String user;
        String pass;
        if (props.apiKeySid() != null && !props.apiKeySid().isBlank()
                && props.apiKeySecret() != null && !props.apiKeySecret().isBlank()) {
            user = props.apiKeySid();
            pass = props.apiKeySecret();
        } else {
            user = props.accountSid();
            pass = props.authToken();
        }
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode f = node.get(field);
        if (f == null || f.isNull()) return null;
        String s = f.asText(null);
        return s == null || s.isBlank() ? null : s;
    }
}
