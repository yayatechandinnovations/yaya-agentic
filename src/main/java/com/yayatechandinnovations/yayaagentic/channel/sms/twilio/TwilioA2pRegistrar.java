package com.yayatechandinnovations.yayaagentic.channel.sms.twilio;

import com.fasterxml.jackson.databind.JsonNode;
import com.yayatechandinnovations.yayaagentic.channel.sms.A2pRegistrar;
import com.yayatechandinnovations.yayaagentic.channel.sms.A2pSubmitResult;
import com.yayatechandinnovations.yayaagentic.channel.sms.BrandStatus;
import com.yayatechandinnovations.yayaagentic.channel.sms.CampaignStatus;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsBrand;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsCampaign;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * Real Twilio A2P 10DLC {@link A2pRegistrar} (bean {@code twilioA2pRegistrar},
 * config-gated). Registers a tenant Brand + Campaign with The Campaign Registry
 * via Twilio's Messaging API, under the platform's ISV identity.
 *
 * <p><b>Prerequisite (operator, one-time):</b> the platform's TrustHub Primary
 * Business Profile and the per-Brand <em>Customer Profile</em> + <em>A2P
 * Profile</em> bundles must exist — that vetting flow is partly manual in the
 * Twilio Console and is NOT automated here. Their SIDs are supplied via
 * {@code yaya.agentic.channels.twilio.a2p.customer-profile-bundle-sid} and
 * {@code ...a2p-profile-bundle-sid}. Without them, {@link #submitBrand} returns
 * a clear {@code Rejected} rather than a confusing API error.
 *
 * <p><b>Verification:</b> these are live Twilio calls that cannot run in CI. The
 * request shapes follow Twilio's documented A2P endpoints; verify end-to-end
 * against a Twilio account before relying on this in production. The dev
 * {@code DevNoopA2pRegistrar} remains the default until this bean registers.
 */
@Component("twilioA2pRegistrar")
@ConditionalOnProperty(name = "yaya.agentic.channels.twilio.account-sid")
public class TwilioA2pRegistrar implements A2pRegistrar {

    private static final Logger log = LoggerFactory.getLogger(TwilioA2pRegistrar.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final TwilioProperties props;
    private final WebClient.Builder webClientBuilder;
    private final String customerProfileBundleSid;
    private final String a2pProfileBundleSid;

    public TwilioA2pRegistrar(
            TwilioProperties props,
            WebClient.Builder webClientBuilder,
            @Value("${yaya.agentic.channels.twilio.a2p.customer-profile-bundle-sid:}") String customerProfileBundleSid,
            @Value("${yaya.agentic.channels.twilio.a2p.a2p-profile-bundle-sid:}") String a2pProfileBundleSid) {
        this.props = props;
        this.webClientBuilder = webClientBuilder;
        this.customerProfileBundleSid = customerProfileBundleSid;
        this.a2pProfileBundleSid = a2pProfileBundleSid;
    }

    @Override
    public boolean isReady() {
        return props.isConfigured();
    }

    @Override
    public A2pSubmitResult submitBrand(SmsBrand brand) {
        if (isBlank(customerProfileBundleSid) || isBlank(a2pProfileBundleSid)) {
            return new A2pSubmitResult.Rejected("trusthub_bundles_missing",
                    "Configure the TrustHub customer-profile + a2p-profile bundle SIDs before registering a brand "
                    + "(see TwilioA2pRegistrar javadoc — this step is an operator prerequisite).");
        }
        try {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("CustomerProfileBundleSid", customerProfileBundleSid);
            form.add("A2PProfileBundleSid", a2pProfileBundleSid);
            JsonNode resp = post("https://messaging.twilio.com/v1/a2p/BrandRegistrations", form);
            String sid = textOf(resp, "sid");
            String status = textOf(resp, "status");
            if (sid == null) return new A2pSubmitResult.TransientFailure("no_sid", "BrandRegistration response lacked sid");
            return new A2pSubmitResult.Accepted(sid, null, status == null ? "PENDING" : status);
        } catch (WebClientResponseException e) {
            return mapSubmitError(e);
        } catch (Exception e) {
            return new A2pSubmitResult.TransientFailure("transport_error", e.getMessage());
        }
    }

    @Override
    public A2pSubmitResult submitCampaign(SmsCampaign campaign, SmsBrand brand) {
        try {
            // 1. A Messaging Service is the attach point for the campaign + numbers.
            LinkedMultiValueMap<String, String> svc = new LinkedMultiValueMap<>();
            svc.add("FriendlyName", "yaya-" + campaign.id().value());
            JsonNode service = post("https://messaging.twilio.com/v1/Services", svc);
            String messagingServiceSid = textOf(service, "sid");
            if (messagingServiceSid == null) {
                return new A2pSubmitResult.TransientFailure("no_service_sid", "Messaging Service creation lacked sid");
            }

            // 2. Register the US A2P campaign on that service.
            LinkedMultiValueMap<String, String> camp = new LinkedMultiValueMap<>();
            camp.add("BrandRegistrationSid", brand.twilioProfileSid() == null ? "" : brand.twilioProfileSid());
            camp.add("Description", campaign.description());
            for (String sample : campaign.sampleMessages()) camp.add("MessageSamples", sample);
            camp.add("UsAppToPersonUsecase", mapUseCase(campaign));
            camp.add("HasEmbeddedLinks", "false");
            camp.add("HasEmbeddedPhone", "false");
            JsonNode usa2p = post("https://messaging.twilio.com/v1/Services/" + messagingServiceSid + "/Compliance/Usa2p", camp);
            String campaignSid = textOf(usa2p, "sid");
            String status = textOf(usa2p, "campaign_status");
            return new A2pSubmitResult.Accepted(campaignSid == null ? messagingServiceSid : campaignSid,
                    messagingServiceSid, status == null ? "PENDING" : status);
        } catch (WebClientResponseException e) {
            return mapSubmitError(e);
        } catch (Exception e) {
            return new A2pSubmitResult.TransientFailure("transport_error", e.getMessage());
        }
    }

    @Override
    public BrandPoll pollBrand(SmsBrand brand) {
        try {
            JsonNode resp = get("https://messaging.twilio.com/v1/a2p/BrandRegistrations/" + brand.twilioProfileSid());
            String status = textOf(resp, "status");           // PENDING | APPROVED | FAILED
            return new BrandPoll(mapBrandStatus(status), textOf(resp, "tcr_id"),
                    "FAILED".equalsIgnoreCase(status) ? "brand_failed" : null,
                    textOf(resp, "failure_reason"));
        } catch (Exception e) {
            return new BrandPoll(brand.status(), brand.tcrBrandId(), null, null);   // transient: hold
        }
    }

    @Override
    public CampaignPoll pollCampaign(SmsCampaign campaign) {
        try {
            JsonNode resp = get("https://messaging.twilio.com/v1/Services/"
                    + campaign.messagingServiceSid() + "/Compliance/Usa2p");
            String status = textOf(resp, "campaign_status"); // PENDING | VERIFIED | FAILED
            return new CampaignPoll(mapCampaignStatus(status),
                    "FAILED".equalsIgnoreCase(status) ? "campaign_failed" : null,
                    textOf(resp, "failure_reason"));
        } catch (Exception e) {
            return new CampaignPoll(campaign.status(), null, null);   // transient: hold
        }
    }

    /* ------------------------------ mapping ------------------------------- */

    private static BrandStatus mapBrandStatus(String raw) {
        if (raw == null) return BrandStatus.PENDING_VERIFICATION;
        return switch (raw.toUpperCase()) {
            case "APPROVED", "REGISTERED" -> BrandStatus.VERIFIED;
            case "FAILED"                 -> BrandStatus.FAILED;
            default                        -> BrandStatus.PENDING_VERIFICATION;
        };
    }

    private static CampaignStatus mapCampaignStatus(String raw) {
        if (raw == null) return CampaignStatus.PENDING_REVIEW;
        return switch (raw.toUpperCase()) {
            case "VERIFIED", "APPROVED" -> CampaignStatus.APPROVED;
            case "FAILED"               -> CampaignStatus.FAILED;
            default                      -> CampaignStatus.PENDING_REVIEW;
        };
    }

    /** Maps our use-case enum to Twilio's us_app_to_person_usecase strings. */
    private static String mapUseCase(SmsCampaign c) {
        return switch (c.useCase()) {
            case TWO_FACTOR_AUTH        -> "2FA";
            case ACCOUNT_NOTIFICATION   -> "ACCOUNT_NOTIFICATION";
            case CUSTOMER_CARE          -> "CUSTOMER_CARE";
            case DELIVERY_NOTIFICATION  -> "DELIVERY_NOTIFICATION";
            case MARKETING              -> "MARKETING";
            case POLLING_VOTING         -> "POLLING_VOTING";
            case PUBLIC_SERVICE_ANNOUNCEMENT -> "PUBLIC_SERVICE_ANNOUNCEMENT";
            case GAMBLING, SWEEPSTAKES, HIGH_RISK_FINANCIAL -> "SPECIAL"; // carrier-reviewed special use cases
        };
    }

    private A2pSubmitResult mapSubmitError(WebClientResponseException e) {
        boolean retriable = e.getStatusCode().is5xxServerError() || e.getStatusCode().value() == 429;
        if (retriable) return new A2pSubmitResult.TransientFailure("http_" + e.getStatusCode().value(), e.getMessage());
        return new A2pSubmitResult.Rejected("http_" + e.getStatusCode().value(), e.getResponseBodyAsString());
    }

    /* ------------------------------- http --------------------------------- */

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

    private static String textOf(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
