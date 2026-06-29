package com.yayatechandinnovations.yayaagentic.channel.sms.twilio;

import com.fasterxml.jackson.databind.JsonNode;
import com.yayatechandinnovations.yayaagentic.channel.sms.SenderProvisionResult;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsNumberProvisioner;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioProperties;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
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
 * Real Twilio {@link SmsNumberProvisioner}. "Provisioning" here configures an
 * already-owned Twilio number: it looks the number up by E.164, then points its
 * {@code SmsUrl} at this platform's inbound webhook for that sender. Poll reports
 * whether the number is present at Twilio (the campaign-APPROVED gate is layered
 * on top by {@code SmsSenderService}).
 *
 * <p>Bean name {@code twilioSmsNumberProvisioner} so the dev stub
 * ({@code @ConditionalOnMissingBean(name="twilioSmsNumberProvisioner")}) backs
 * off; {@link ConditionalOnProperty} so it only registers when Twilio is set.
 *
 * <p>NOTE: issues live Twilio API calls; verify against a Twilio account. Buying
 * a brand-new number (AvailablePhoneNumbers search + purchase) is a follow-on —
 * v1 configures numbers already in the account.
 */
@Component("twilioSmsNumberProvisioner")
@ConditionalOnProperty(name = "yaya.agentic.channels.twilio.account-sid")
public class TwilioSmsNumberProvisioner implements SmsNumberProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsNumberProvisioner.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String INBOUND_PATH = "/v1/channels/sms/twilio/webhook/";

    private final TwilioProperties props;
    private final YayaAgenticProperties yaya;
    private final WebClient.Builder webClientBuilder;

    public TwilioSmsNumberProvisioner(TwilioProperties props,
                                      YayaAgenticProperties yaya,
                                      WebClient.Builder webClientBuilder) {
        this.props = props;
        this.yaya = yaya;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public boolean isReady() {
        return props.isConfigured();
    }

    @Override
    public SenderProvisionResult provision(SmsSender sender) {
        try {
            JsonNode found = lookup(sender.phoneE164());
            if (found == null) {
                return new SenderProvisionResult.Rejected("number_not_found",
                        "number " + sender.phoneE164() + " is not in the Twilio account");
            }
            String numberSid = found.get("sid").asText();
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("SmsUrl", webhookUrl(sender));
            form.add("SmsMethod", "POST");
            post(accountResource("/IncomingPhoneNumbers/" + numberSid + ".json"), form);
            return new SenderProvisionResult.Accepted(numberSid, "configured");
        } catch (WebClientResponseException e) {
            boolean retriable = e.getStatusCode().is5xxServerError() || e.getStatusCode().value() == 429;
            if (retriable) return new SenderProvisionResult.TransientFailure("http_" + e.getStatusCode().value(), e.getMessage());
            return new SenderProvisionResult.Rejected("http_" + e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (Exception e) {
            return new SenderProvisionResult.TransientFailure("transport_error", e.getMessage());
        }
    }

    @Override
    public ProvisionPoll poll(SmsSender sender) {
        try {
            JsonNode found = lookup(sender.phoneE164());
            if (found == null) return new ProvisionPoll(false, "number_not_found", "number no longer in account");
            return new ProvisionPoll(true, null, null);
        } catch (Exception e) {
            return new ProvisionPoll(false, null, e.getMessage());   // transient: no transition
        }
    }

    @Override
    public void revoke(SmsSender sender) {
        try {
            JsonNode found = lookup(sender.phoneE164());
            if (found == null) return;
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("SmsUrl", "");   // detach the webhook
            post(accountResource("/IncomingPhoneNumbers/" + found.get("sid").asText() + ".json"), form);
        } catch (Exception e) {
            log.warn("Twilio SMS revoke (webhook detach) failed for {}: {}", sender.phoneE164(), e.getMessage());
        }
    }

    /* ------------------------------ helpers ------------------------------- */

    private JsonNode lookup(String e164) {
        String url = accountResource("/IncomingPhoneNumbers.json") + "?PhoneNumber=" + e164;
        JsonNode resp = get(url);
        JsonNode list = resp == null ? null : resp.get("incoming_phone_numbers");
        return list != null && list.isArray() && list.size() > 0 ? list.get(0) : null;
    }

    private String webhookUrl(SmsSender sender) {
        String base = props.webhookBaseUrl() == null || props.webhookBaseUrl().isBlank()
                ? yaya.publicBaseUrl() : props.webhookBaseUrl();
        return base + INBOUND_PATH + sender.id().value();
    }

    private String accountResource(String suffix) {
        return props.effectiveApiBaseUrl() + "/2010-04-01/Accounts/" + props.accountSid() + suffix;
    }

    private String basicAuth() {
        String user = notBlank(props.apiKeySid()) ? props.apiKeySid() : props.accountSid();
        String secret = notBlank(props.apiKeySid()) ? props.apiKeySecret() : props.authToken();
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode get(String url) {
        return webClientBuilder.clone().build().get().uri(url)
                .header(HttpHeaders.AUTHORIZATION, basicAuth())
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .retrieve().bodyToMono(JsonNode.class).block(TIMEOUT);
    }

    private JsonNode post(String url, LinkedMultiValueMap<String, String> form) {
        return webClientBuilder.clone().build().post().uri(url)
                .header(HttpHeaders.AUTHORIZATION, basicAuth())
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve().bodyToMono(JsonNode.class).block(TIMEOUT);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
