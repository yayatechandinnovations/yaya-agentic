package com.yayatechandinnovations.yayaagentic.channel.sms.twilio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsMessage;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSendException;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsTransport;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioProperties;
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
 * Real Twilio Programmable SMS transport — the production {@link SmsTransport}
 * used for BOTH the OTP/step-up path and the conversational SMS channel. POSTs
 * to Twilio's {@code Messages.json} over the same WebClient + Basic-auth pattern
 * the WhatsApp provider uses ({@code TwilioMessagingChannelProvider.postMessage}).
 *
 * <p>{@link ConditionalOnProperty} on {@code account-sid} so it only registers
 * when Twilio is configured; otherwise {@code DevConsoleSmsTransport} (the
 * {@code @ConditionalOnMissingBean} fallback) stays in place. Registering this
 * bean supersedes the dev console for the whole platform.
 *
 * <p>NOTE: this issues live Twilio API calls and cannot be exercised in CI
 * without credentials — verify against a Twilio test account before relying on
 * it in production.
 */
@Component
@ConditionalOnProperty(name = "yaya.agentic.channels.twilio.account-sid")
public class TwilioSmsTransport implements SmsTransport {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsTransport.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final TwilioProperties props;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper json;

    public TwilioSmsTransport(TwilioProperties props,
                              WebClient.Builder webClientBuilder,
                              ObjectMapper json) {
        this.props = props;
        this.webClientBuilder = webClientBuilder;
        this.json = json;
    }

    @Override
    public String name() {
        return "twilio-sms";
    }

    @Override
    public void send(SmsMessage msg) throws SmsSendException {
        String url = props.effectiveApiBaseUrl()
                + "/2010-04-01/Accounts/" + props.accountSid() + "/Messages.json";
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                (authUser() + ":" + authSecret()).getBytes(StandardCharsets.UTF_8));

        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", msg.toPhone());
        // Prefer a Messaging Service (pooling + sticky sender) when configured,
        // else address by the sender's own number.
        if (props.messagingServiceSid() != null && !props.messagingServiceSid().isBlank()) {
            form.add("MessagingServiceSid", props.messagingServiceSid());
        } else {
            form.add("From", msg.fromPhone());
        }
        form.add("Body", msg.bodyText());

        try {
            JsonNode resp = webClientBuilder.clone().build().post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
            String sid = resp == null ? null : (resp.hasNonNull("sid") ? resp.get("sid").asText() : null);
            if (sid == null || sid.isBlank()) {
                throw new SmsSendException("Twilio SMS response lacked sid", false);
            }
            log.debug("Twilio SMS sent sid={} to={}", sid, msg.toPhone());
        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            boolean retriable = status.is5xxServerError() || status.value() == 429 || status.value() == 408;
            throw new SmsSendException("Twilio SMS send failed: " + describe(e), e, retriable);
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            throw new SmsSendException("Twilio SMS transport error: " + e.getMessage(), e, true);
        }
    }

    private String authUser() {
        return notBlank(props.apiKeySid()) ? props.apiKeySid() : props.accountSid();
    }

    private String authSecret() {
        return notBlank(props.apiKeySid()) ? props.apiKeySecret() : props.authToken();
    }

    private String describe(WebClientResponseException e) {
        try {
            JsonNode err = json.readTree(e.getResponseBodyAsString());
            String code = err.hasNonNull("code") ? err.get("code").asText() : String.valueOf(e.getStatusCode().value());
            String message = err.hasNonNull("message") ? err.get("message").asText() : e.getMessage();
            return "[" + code + "] " + message;
        } catch (Exception ignored) {
            return "HTTP " + e.getStatusCode().value();
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
