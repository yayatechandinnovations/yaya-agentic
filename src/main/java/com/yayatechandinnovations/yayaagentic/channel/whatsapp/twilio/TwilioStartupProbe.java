package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * On boot, if {@link TwilioProperties#isConfigured()} is true, this probe
 * fetches {@code https://api.twilio.com/2010-04-01/Accounts/{sid}.json} once
 * and logs the outcome. The point is a fail-fast smoke that the platform
 * Twilio creds are reachable + accepted before the first real submit hits.
 *
 * <p>Failures are LOGGED, not thrown — boot continues. The {@code submit}
 * endpoint independently re-checks readiness via
 * {@link com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver#isReady}.
 * Dev/test environments without Twilio configured see one INFO line at boot
 * and nothing else; production sees either a success line or a loud
 * {@code [SECURITY-ADJACENT]} warning the operator can grep for.
 *
 * <p>Probe scope: account-only. Sender lifecycle calls are still W2 — this
 * probe never touches sender state. See {@code whatsapp-channel-design.md}
 * W1 milestone notes (§21).
 */
@Component
public class TwilioStartupProbe {

    private static final Logger log = LoggerFactory.getLogger(TwilioStartupProbe.class);
    private static final String API_VERSION = "/2010-04-01";

    private final TwilioProperties props;
    private final WebClient.Builder webClientBuilder;

    public TwilioStartupProbe(TwilioProperties props, WebClient.Builder webClientBuilder) {
        this.props = props;
        this.webClientBuilder = webClientBuilder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.isConfigured()) {
            log.info("Twilio not configured — WhatsApp channel is disabled. "
                    + "Set yaya.agentic.channels.twilio.{account-sid,api-key-sid,api-key-secret} "
                    + "to enable.");
            return;
        }
        probe();
    }

    void probe() {
        String url = props.effectiveApiBaseUrl() + API_VERSION + "/Accounts/"
                + props.accountSid() + ".json";
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                (props.apiKeySid() + ":" + props.apiKeySecret()).getBytes(StandardCharsets.UTF_8));

        try {
            WebClient client = webClientBuilder.build();
            Integer status = client.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth)
                    .exchangeToMono(resp -> resp.releaseBody()
                            .thenReturn(resp.statusCode().value()))
                    .block(Duration.ofSeconds(8));
            if (status != null && status >= 200 && status < 300) {
                log.info("Twilio readiness probe OK (account {} reachable).",
                        maskSid(props.accountSid()));
            } else {
                log.warn("[SECURITY-ADJACENT] Twilio readiness probe returned HTTP {} for account {}. "
                                + "WhatsApp submits will fail until creds are corrected.",
                        status, maskSid(props.accountSid()));
            }
        } catch (Exception e) {
            log.warn("[SECURITY-ADJACENT] Twilio readiness probe failed for account {}: {}. "
                            + "WhatsApp submits will fail until creds are corrected.",
                    maskSid(props.accountSid()), e.getMessage());
        }
    }

    private static String maskSid(String sid) {
        if (sid == null || sid.length() < 8) return "***";
        return sid.substring(0, 4) + "…" + sid.substring(sid.length() - 4);
    }
}
