package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.InboundValidation;
import com.yayatechandinnovations.yayaagentic.channel.MessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.channel.OutboundFreeform;
import com.yayatechandinnovations.yayaagentic.channel.OutboundResult;
import com.yayatechandinnovations.yayaagentic.channel.OutboundTemplate;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCreds;
import com.yayatechandinnovations.yayaagentic.channel.ProviderId;
import com.yayatechandinnovations.yayaagentic.channel.RawInboundRequest;
import com.yayatechandinnovations.yayaagentic.channel.SenderRef;
import com.yayatechandinnovations.yayaagentic.channel.SenderRegistrationRequest;
import com.yayatechandinnovations.yayaagentic.channel.SenderRegistrationResult;
import com.yayatechandinnovations.yayaagentic.channel.SenderSyncResult;
import com.yayatechandinnovations.yayaagentic.channel.SenderTrustMaterial;
import com.yayatechandinnovations.yayaagentic.channel.TemplateSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Twilio-backed {@link MessagingChannelProvider}. Speaks the Channel Senders
 * API on {@code messaging.twilio.com} for sender lifecycle (this milestone)
 * and will speak the Messages API on {@code api.twilio.com} for outbound
 * dispatch (W5). Stateless across calls — every method receives the per-call
 * {@link ProviderCreds} resolved by the platform-singleton resolver.
 *
 * <p>Mapping rules:
 * <ul>
 *   <li>HTTP {@code 2xx} → {@link SenderRegistrationResult.Accepted} /
 *       {@link SenderSyncResult}</li>
 *   <li>HTTP {@code 4xx} where Twilio's {@code code} field is present →
 *       {@link SenderRegistrationResult.Rejected} (operator must edit + resubmit)</li>
 *   <li>HTTP {@code 5xx}, {@code 429}, timeouts, network errors →
 *       {@link SenderRegistrationResult.TransientFailure} (poller / retry)</li>
 * </ul>
 *
 * <p>See {@code whatsapp-channel-design.md} §6.1 (state machine outcomes) +
 * §6.4 (polling).
 */
@Component
public class TwilioMessagingChannelProvider implements MessagingChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(TwilioMessagingChannelProvider.class);

    /** Channel Senders endpoint path on {@code messaging.twilio.com}. */
    private static final String SENDERS_PATH = "/v2/Channels/Senders";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    private final TwilioProperties props;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper json;

    public TwilioMessagingChannelProvider(TwilioProperties props,
                                          WebClient.Builder webClientBuilder,
                                          ObjectMapper json) {
        this.props = props;
        this.webClientBuilder = webClientBuilder;
        this.json = json;
    }

    @Override public ProviderId id() { return ProviderId.TWILIO; }

    @Override public Set<ChannelKind> supports() {
        return Set.of(new ChannelKind.WhatsApp(), new ChannelKind.Sms());
    }

    /* --------------------------- registerSender (§6) --------------------- */

    @Override
    public SenderRegistrationResult registerSender(SenderRegistrationRequest req,
                                                   ProviderCreds creds) {
        ensureCreds(creds);
        if (!(req.channel() instanceof ChannelKind.WhatsApp)) {
            return new SenderRegistrationResult.Rejected(
                    "unsupported_channel",
                    "Twilio Channel Senders v2 only handles WhatsApp in this build");
        }

        String senderAddress = "whatsapp:" + req.phoneE164();
        Map<String, Object> webhook = webhookConfig(req);

        // Twilio refuses to register a number twice on one account. The first
        // sender of a direct account must come from the Console's Self
        // Sign-up, and a submit whose response was lost leaves a sender we
        // never recorded — both are adopted instead of re-created.
        Optional<JsonNode> existing = Optional.empty();
        try {
            existing = findSenderByAddress(senderAddress, creds);
        } catch (Exception e) {
            log.debug("Twilio sender lookup failed for phone {} (registering fresh): {}",
                    maskPhone(req.phoneE164()), e.getMessage());
        }
        if (existing.isPresent()) {
            return adoptSender(existing.get(), webhook, req, creds);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender_id", senderAddress);
        Map<String, Object> configuration = new LinkedHashMap<>();
        // Tech Provider / Embedded Signup path: the WABA captured by Meta's
        // popup links the sender to the customer's WhatsApp Business Account,
        // and ownership was already verified inside the popup.
        Object wabaId = req.providerPayload().get("wabaId");
        if (wabaId instanceof String w && !w.isBlank()) {
            configuration.put("waba_id", w);
        } else if (req.providerPayload().get("verificationMethod") instanceof String m && !m.isBlank()) {
            configuration.put("verification_method", m);
        }
        if (!configuration.isEmpty()) {
            body.put("configuration", configuration);
        }
        body.put("profile", profileFromPayload(req));
        body.put("webhook", webhook);

        try {
            JsonNode resp = post(messagingUrl(SENDERS_PATH), body, creds, req.idempotencyKey());
            String sid = textOrThrow(resp, "sid", "Twilio Senders response missing 'sid'");
            String status = text(resp, "status", "PENDING");
            return new SenderRegistrationResult.Accepted(
                    new SenderRef(ProviderId.TWILIO, sid), status);
        } catch (WebClientResponseException e) {
            return mapRegistrationHttpError(e);
        } catch (Exception e) {
            log.warn("Twilio registerSender transport failure for phone {}: {}",
                    maskPhone(req.phoneE164()), e.getMessage());
            return new SenderRegistrationResult.TransientFailure(
                    "transport_error", e.getMessage());
        }
    }

    /* --------------------------- syncSender ------------------------------ */

    @Override
    public SenderSyncResult syncSender(SenderRef ref, ProviderCreds creds) {
        ensureCreds(creds);
        ensureSameProvider(ref);

        try {
            JsonNode resp = get(messagingUrl(SENDERS_PATH + "/" + ref.externalId()), creds);
            String status = text(resp, "status", "UNKNOWN");
            String approvedName = nullableText(resp.at("/profile/name"));
            String failureCode = null;
            String failureMessage = null;
            JsonNode reasons = resp.path("offline_reasons");
            if (reasons.isArray() && !reasons.isEmpty()) {
                failureCode = nullableText(reasons.get(0).get("code"));
                List<String> messages = new java.util.ArrayList<>();
                for (JsonNode r : reasons) {
                    String m = nullableText(r.get("message"));
                    if (m != null) messages.add(m);
                }
                failureMessage = messages.isEmpty() ? null : String.join("; ", messages);
            }
            return new SenderSyncResult(ref, status, approvedName,
                    failureCode, failureMessage, Instant.now());
        } catch (WebClientResponseException e) {
            // GET-side mapping: 404 / 401 / 403 are terminal; 5xx / 429 are
            // transient and the poller will retry. We surface a typed result
            // even for terminal errors so the service layer can FAIL the row
            // rather than swallowing the failure silently.
            String code = e.getStatusCode().is5xxServerError() || e.getStatusCode().value() == 429
                    ? "transient_http_" + e.getStatusCode().value()
                    : "http_" + e.getStatusCode().value();
            return new SenderSyncResult(ref, "UNKNOWN", null,
                    code, e.getResponseBodyAsString(), Instant.now());
        } catch (Exception e) {
            log.warn("Twilio syncSender transport failure for {}: {}",
                    ref.externalId(), e.getMessage());
            return new SenderSyncResult(ref, "UNKNOWN", null,
                    "transport_error", e.getMessage(), Instant.now());
        }
    }

    /* --------------------------- revokeSender ---------------------------- */

    @Override
    public void revokeSender(SenderRef ref, ProviderCreds creds) {
        ensureCreds(creds);
        ensureSameProvider(ref);
        // DELETE is idempotent in Twilio — 404 means already gone, treat as
        // success. Any other 4xx means the operator-facing service layer
        // already gated this call and Twilio still refuses; we surface it.
        try {
            buildClient(creds).delete()
                    .uri(messagingUrl(SENDERS_PATH + "/" + ref.externalId()))
                    .retrieve()
                    .toBodilessEntity()
                    .block(HTTP_TIMEOUT);
        } catch (WebClientResponseException.NotFound ignored) {
            log.debug("Twilio revokeSender 404 for {} — treating as already revoked",
                    ref.externalId());
        } catch (WebClientResponseException e) {
            throw new TwilioApiException(
                    "twilio_revoke_failed",
                    "Twilio rejected revoke for " + ref.externalId() + ": "
                            + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new TwilioApiException(
                    "twilio_transport_error",
                    "Could not reach Twilio to revoke " + ref.externalId() + ": " + e.getMessage());
        }
    }

    /* --------------------------- verifySender ---------------------------- */

    @Override
    public SenderSyncResult verifySender(SenderRef ref, String verificationCode, ProviderCreds creds) {
        ensureCreds(creds);
        ensureSameProvider(ref);
        try {
            JsonNode resp = postJson(messagingUrl(SENDERS_PATH + "/" + ref.externalId()),
                    Map.of("configuration", Map.of("verification_code", verificationCode)), creds);
            return new SenderSyncResult(ref, text(resp, "status", "VERIFYING"),
                    null, null, null, Instant.now());
        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            boolean retriable = status.is5xxServerError() || status.value() == 429 || status.value() == 408;
            throw new TwilioApiException(
                    retriable ? "twilio_unavailable" : "twilio_verification_rejected",
                    twilioErrorMessage(e));
        } catch (Exception e) {
            throw new TwilioApiException("twilio_unavailable",
                    "Could not reach Twilio to verify " + ref.externalId() + ": " + e.getMessage());
        }
    }

    /* --------------------------- inbound (W4) ---------------------------- */

    @Override
    public InboundValidation validateInbound(RawInboundRequest req,
                                             SenderTrustMaterial trust) {
        // Subaccount-owned senders are signed with their subaccount's auth
        // token; platform-singleton senders with the platform token.
        String signingToken = trust.signingAuthToken() != null && !trust.signingAuthToken().isBlank()
                ? trust.signingAuthToken()
                : props.authToken();
        if (signingToken == null || signingToken.isBlank()) {
            return new InboundValidation.Invalid(
                    "auth_token_unset",
                    "yaya.agentic.channels.twilio.auth-token is unset — Twilio webhooks cannot be verified");
        }
        String presentedSig = headerValue(req, "x-twilio-signature");
        if (presentedSig == null || presentedSig.isBlank()) {
            return new InboundValidation.Invalid(
                    "signature_missing",
                    "X-Twilio-Signature header absent");
        }
        // Resolve the canonical URL from platform config + path, NOT from
        // the request's Host / X-Forwarded-* headers — see §16, §2 corollary
        // #2. A malicious proxy must not be able to force a verify by
        // rewriting Host.
        String url = props.webhookBaseUrl() == null || props.webhookBaseUrl().isBlank()
                ? null
                : trim(props.webhookBaseUrl()) + req.path();
        if (url == null) {
            return new InboundValidation.Invalid(
                    "webhook_base_url_unset",
                    "yaya.agentic.channels.twilio.webhook-base-url is unset — cannot reconstruct canonical URL");
        }
        boolean ok = TwilioSignatureVerifier.verify(
                url, req.formParams(), signingToken, presentedSig);
        if (!ok) {
            return new InboundValidation.Invalid(
                    "signature_mismatch",
                    "X-Twilio-Signature did not match the expected HMAC");
        }
        return InboundValidation.Valid.INSTANCE;
    }

    private static String headerValue(RawInboundRequest req, String name) {
        for (var entry : req.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private static String trim(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /* --------------------------- outbound (W5) --------------------------- */

    @Override
    public OutboundResult sendFreeform(SenderRef ref, OutboundFreeform msg, ProviderCreds creds) {
        ensureCreds(creds);
        TwilioCreds tc = (TwilioCreds) creds;
        org.springframework.util.LinkedMultiValueMap<String, String> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("To", "whatsapp:" + msg.recipientE164());
        if (!addFromAddressing(form, tc, msg.providerHints())) {
            return noFromAddressing();
        }
        form.add("Body", msg.body() == null ? "" : msg.body());
        Object statusCallback = msg.providerHints().get("statusCallbackUrl");
        if (statusCallback instanceof String s && !s.isBlank()) {
            form.add("StatusCallback", s);
        }
        return postMessage(tc, form);
    }

    @Override
    public OutboundResult sendMedia(SenderRef ref,
                                    com.yayatechandinnovations.yayaagentic.channel.OutboundMedia msg,
                                    ProviderCreds creds) {
        ensureCreds(creds);
        TwilioCreds tc = (TwilioCreds) creds;
        org.springframework.util.LinkedMultiValueMap<String, String> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("To", "whatsapp:" + msg.recipientE164());
        if (!addFromAddressing(form, tc, msg.providerHints())) {
            return noFromAddressing();
        }
        // Twilio fetches the media from this (signed, short-lived) URL.
        form.add("MediaUrl", msg.mediaUrl());
        if (msg.caption() != null && !msg.caption().isBlank()) {
            form.add("Body", msg.caption());
        }
        Object statusCallback = msg.providerHints().get("statusCallbackUrl");
        if (statusCallback instanceof String s && !s.isBlank()) {
            form.add("StatusCallback", s);
        }
        return postMessage(tc, form);
    }

    @Override
    public OutboundResult sendTemplate(SenderRef ref, OutboundTemplate msg, ProviderCreds creds) {
        ensureCreds(creds);
        TwilioCreds tc = (TwilioCreds) creds;
        Object contentSid = msg.providerHints().get("contentSid");
        if (!(contentSid instanceof String csid) || csid.isBlank()) {
            // The W6 template registry stores the Twilio ContentSid alongside
            // each approved template; the dispatcher resolves it before
            // calling sendTemplate. A blank value here means the operator
            // tried to send a template that hasn't been registered with
            // Twilio's Content API yet.
            return new OutboundResult.ProviderFailure(
                    "missing_content_sid",
                    "Template send requires providerHints.contentSid (set by the template registry once Twilio approves the template)",
                    false);
        }
        org.springframework.util.LinkedMultiValueMap<String, String> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("To", "whatsapp:" + msg.recipientE164());
        if (!addFromAddressing(form, tc, msg.providerHints())) {
            return noFromAddressing();
        }
        form.add("ContentSid", csid);
        if (!msg.variables().isEmpty()) {
            Map<String, String> vars = new LinkedHashMap<>();
            for (int i = 0; i < msg.variables().size(); i++) {
                vars.put(String.valueOf(i + 1), msg.variables().get(i));
            }
            try {
                form.add("ContentVariables", json.writeValueAsString(vars));
            } catch (Exception e) {
                return new OutboundResult.ProviderFailure(
                        "bad_template_variables", e.getMessage(), false);
            }
        }
        return postMessage(tc, form);
    }

    private OutboundResult postMessage(TwilioCreds tc,
                                       org.springframework.util.LinkedMultiValueMap<String, String> form) {
        String url = props.effectiveApiBaseUrl()
                + "/2010-04-01/Accounts/" + tc.accountSid() + "/Messages.json";
        String basicAuth = tc.basicAuthHeader();
        try {
            JsonNode resp = webClientBuilder.clone().build().post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(org.springframework.web.reactive.function.BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(HTTP_TIMEOUT);
            String sid = text(resp, "sid", null);
            if (sid == null || sid.isBlank()) {
                return new OutboundResult.ProviderFailure(
                        "missing_sid", "Twilio response lacked sid", false);
            }
            return new OutboundResult.Sent(sid, Instant.now());
        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            boolean retriable = status.is5xxServerError() || status.value() == 429 || status.value() == 408;
            String code = retriable ? "transient_http_" + status.value() : "http_" + status.value();
            String message = e.getResponseBodyAsString();
            try {
                JsonNode err = json.readTree(message);
                String twilioCode = nullableText(err.get("code"));
                String twilioMsg = nullableText(err.get("message"));
                if (twilioCode != null) code = "twilio_" + twilioCode;
                if (twilioMsg != null) message = twilioMsg;
            } catch (Exception ignored) {}
            return new OutboundResult.ProviderFailure(code, message, retriable);
        } catch (Exception e) {
            return new OutboundResult.ProviderFailure(
                    "transport_error", e.getMessage(), true);
        }
    }

    /**
     * Send a Content API message (template or in-session interactive) with
     * named variables. Used by the WhatsApp outreach leg and by native
     * in-window buttons (whatsapp-ops-integration-design.md §6.2, GAP-W3).
     */
    public OutboundResult sendContent(String recipientE164, String contentSid,
                                      Map<String, String> variables,
                                      Map<String, Object> providerHints, ProviderCreds creds) {
        ensureCreds(creds);
        TwilioCreds tc = (TwilioCreds) creds;
        org.springframework.util.LinkedMultiValueMap<String, String> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("To", "whatsapp:" + recipientE164);
        if (!addFromAddressing(form, tc, providerHints)) {
            return noFromAddressing();
        }
        form.add("ContentSid", contentSid);
        if (variables != null && !variables.isEmpty()) {
            try {
                form.add("ContentVariables", json.writeValueAsString(variables));
            } catch (Exception e) {
                return new OutboundResult.ProviderFailure("bad_template_variables", e.getMessage(), false);
            }
        }
        if (providerHints.get("statusCallbackUrl") instanceof String s && !s.isBlank()) {
            form.add("StatusCallback", s);
        }
        return postMessage(tc, form);
    }

    /* --------------------------- Content API (§6.3) ---------------------- */

    /** Create a Content resource; returns its {@code HX…} sid. */
    public String createContent(Map<String, Object> body, ProviderCreds creds) {
        ensureCreds(creds);
        JsonNode resp = buildClient(creds).post()
                .uri(props.effectiveContentBaseUrl() + "/v1/Content")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(HTTP_TIMEOUT);
        return textOrThrow(resp, "sid", "Twilio Content response missing 'sid'");
    }

    /** The sid of an existing Content resource with this friendly name, if any. */
    public java.util.Optional<String> findContentByName(String friendlyName, ProviderCreds creds) {
        ensureCreds(creds);
        String next = props.effectiveContentBaseUrl() + "/v1/Content?PageSize=500";
        for (int page = 0; page < 10 && next != null; page++) {
            JsonNode resp = get(next, creds);
            for (JsonNode c : resp.path("contents")) {
                if (friendlyName.equals(text(c, "friendly_name", null))) {
                    return java.util.Optional.ofNullable(text(c, "sid", null));
                }
            }
            next = nullableText(resp.at("/meta/next_page_url"));
        }
        return java.util.Optional.empty();
    }

    /** Ask Meta (through Twilio) to approve a Content resource as a WhatsApp template. */
    public ApprovalState requestWhatsAppApproval(String contentSid, String name, String category,
                                                 ProviderCreds creds) {
        ensureCreds(creds);
        JsonNode resp = buildClient(creds).post()
                .uri(props.effectiveContentBaseUrl() + "/v1/Content/" + contentSid + "/ApprovalRequests/whatsapp")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", name, "category", category))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(HTTP_TIMEOUT);
        return new ApprovalState(text(resp, "status", "received"), nullableText(resp.get("rejection_reason")));
    }

    /** Current WhatsApp approval state of a Content resource ({@code unsubmitted} when none). */
    public ApprovalState fetchWhatsAppApproval(String contentSid, ProviderCreds creds) {
        ensureCreds(creds);
        JsonNode resp = get(props.effectiveContentBaseUrl() + "/v1/Content/" + contentSid + "/ApprovalRequests",
                creds);
        JsonNode wa = resp.path("whatsapp");
        if (wa.isMissingNode() || wa.isNull()) return new ApprovalState("unsubmitted", null);
        return new ApprovalState(text(wa, "status", "unsubmitted"), nullableText(wa.get("rejection_reason")));
    }

    /** Twilio's approval verbiage: received, pending, approved, rejected, paused, disabled, unsubmitted. */
    public record ApprovalState(String status, String rejectionReason) {}

    @Override
    public TemplateSyncResult syncTemplates(SenderRef ref, ProviderCreds creds) {
        // Returns an empty template list rather than throwing — the templates
        // tab (W6) can render "no templates synced yet" cleanly while we wait
        // for the milestone to wire this up.
        return new TemplateSyncResult(ref, Instant.now(), List.of());
    }

    /* --------------------------- helpers --------------------------------- */

    /**
     * Senders v2 vocabulary for {@code profile.vertical}, keyed by the codes the
     * admin UI stores. The trailing aliases keep drafts saved under the earlier
     * Meta-style list registrable.
     */
    private static final Map<String, String> TWILIO_VERTICALS = Map.ofEntries(
            Map.entry("AUTO", "Automotive"),
            Map.entry("BEAUTY_SPA_SALON", "Beauty, Spa and Salon"),
            Map.entry("CLOTHING_AND_APPAREL", "Clothing and Apparel"),
            Map.entry("EDUCATION", "Education"),
            Map.entry("ENTERTAINMENT", "Entertainment"),
            Map.entry("EVENT_PLANNING_AND_SERVICE", "Event Planning and Service"),
            Map.entry("FINANCE_AND_BANKING", "Finance and Banking"),
            Map.entry("FOOD_AND_GROCERY", "Food and Grocery"),
            Map.entry("HOTEL_AND_LODGING", "Hotel and Lodging"),
            Map.entry("MEDICAL_AND_HEALTH", "Medical and Health"),
            Map.entry("NON_PROFIT", "Non-profit"),
            Map.entry("PROFESSIONAL_SERVICES", "Professional Services"),
            Map.entry("PUBLIC_SERVICE", "Public Service"),
            Map.entry("RESTAURANT", "Restaurant"),
            Map.entry("SHOPPING_AND_RETAIL", "Shopping and Retail"),
            Map.entry("TRAVEL_AND_TRANSPORTATION", "Travel and Transportation"),
            Map.entry("OTHER", "Other"),
            Map.entry("GOVERNMENT_AND_POLITICS", "Public Service"),
            Map.entry("HEALTH_AND_WELLNESS", "Medical and Health"),
            Map.entry("HOME_IMPROVEMENT", "Professional Services"),
            Map.entry("RETAIL", "Shopping and Retail"));

    static String twilioVertical(String code) {
        if (code == null || code.isBlank()) return "Other";
        String trimmed = code.trim();
        String mapped = TWILIO_VERTICALS.get(trimmed.toUpperCase(java.util.Locale.ROOT));
        if (mapped != null) return mapped;
        return TWILIO_VERTICALS.containsValue(trimmed) ? trimmed : "Other";
    }

    private Map<String, Object> profileFromPayload(SenderRegistrationRequest req) {
        Map<String, Object> payload = req.providerPayload();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", req.displayName());
        copyIfPresent(payload, "about", profile, "about");
        copyIfPresent(payload, "address", profile, "address");
        profile.put("vertical", twilioVertical(payload.get("vertical") instanceof String v ? v : null));
        if (payload.get("contactEmail") instanceof String email && !email.isBlank()) {
            profile.put("emails", List.of(Map.of("email", email, "label", "Email")));
        }
        if (payload.get("websiteUrl") instanceof String site && !site.isBlank()) {
            profile.put("websites", List.of(Map.of("website", site, "label", "Website")));
        }
        return profile;
    }

    private static Map<String, Object> webhookConfig(SenderRegistrationRequest req) {
        return Map.of(
                "callback_url", req.webhookUrl(),
                "callback_method", "POST",
                "status_callback_url", req.statusCallbackUrl() == null ? "" : req.statusCallbackUrl(),
                "status_callback_method", "POST");
    }

    private Optional<JsonNode> findSenderByAddress(String senderAddress, ProviderCreds creds) {
        String next = messagingUrl(SENDERS_PATH + "?Channel=whatsapp&PageSize=1000");
        for (int page = 0; page < 10 && next != null; page++) {
            // next_page_url arrives already encoded; URI.create keeps the
            // PageToken from being encoded a second time.
            JsonNode resp = buildClient(creds).get()
                    .uri(java.net.URI.create(next))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(HTTP_TIMEOUT);
            if (resp == null) return Optional.empty();
            for (JsonNode s : resp.path("senders")) {
                if (senderAddress.equals(text(s, "sender_id", null))) {
                    return Optional.of(s);
                }
            }
            next = nullableText(resp.at("/meta/next_page_url"));
        }
        return Optional.empty();
    }

    /** Re-points an existing sender's webhooks at the platform; its profile is
     *  left untouched so an approved display name isn't sent back to review. */
    private SenderRegistrationResult adoptSender(JsonNode existing, Map<String, Object> webhook,
                                                 SenderRegistrationRequest req, ProviderCreds creds) {
        String sid = text(existing, "sid", null);
        if (sid == null || sid.isBlank()) {
            return new SenderRegistrationResult.TransientFailure(
                    "twilio_bad_response", "Twilio listed the sender without a sid");
        }
        try {
            JsonNode resp = postJson(messagingUrl(SENDERS_PATH + "/" + sid),
                    Map.of("webhook", webhook), creds);
            log.info("Adopted existing Twilio WhatsApp sender {} for phone {}",
                    sid, maskPhone(req.phoneE164()));
            String status = text(resp, "status", text(existing, "status", "PENDING"));
            return new SenderRegistrationResult.Accepted(new SenderRef(ProviderId.TWILIO, sid), status);
        } catch (WebClientResponseException e) {
            return mapRegistrationHttpError(e);
        } catch (Exception e) {
            return new SenderRegistrationResult.TransientFailure("transport_error", e.getMessage());
        }
    }

    /**
     * Pins outbound to the sender's own number. The platform Messaging Service
     * pool is shared by every tenant's senders, so it is only a fallback for
     * callers that carry no sender phone.
     */
    private static boolean addFromAddressing(org.springframework.util.LinkedMultiValueMap<String, String> form,
                                             TwilioCreds tc, Map<String, Object> hints) {
        if (hints.get("fromPhoneE164") instanceof String s && !s.isBlank()) {
            form.add("From", "whatsapp:" + s);
            return true;
        }
        if (tc.messagingServiceSid() != null && !tc.messagingServiceSid().isBlank()) {
            form.add("MessagingServiceSid", tc.messagingServiceSid());
            return true;
        }
        return false;
    }

    private static OutboundResult noFromAddressing() {
        return new OutboundResult.ProviderFailure(
                "no_from_addressing",
                "Either providerHints.fromPhoneE164 or a Messaging Service SID is required",
                false);
    }

    private String twilioErrorMessage(WebClientResponseException e) {
        String body = e.getResponseBodyAsString();
        try {
            String msg = nullableText(json.readTree(body).get("message"));
            if (msg != null) return msg;
        } catch (Exception ignored) {
            // non-JSON body — fall back to the raw text
        }
        return body == null || body.isBlank() ? "HTTP " + e.getStatusCode().value() : body;
    }

    private static void copyIfPresent(Map<String, Object> src, String srcKey,
                                      Map<String, Object> dst, String dstKey) {
        Object v = src.get(srcKey);
        if (v instanceof String s && !s.isBlank()) dst.put(dstKey, s);
    }

    private SenderRegistrationResult mapRegistrationHttpError(WebClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        String body = e.getResponseBodyAsString();
        if (status.is5xxServerError() || status.value() == 429 || status.value() == 408) {
            return new SenderRegistrationResult.TransientFailure(
                    "transient_http_" + status.value(), body);
        }
        // Try to surface Twilio's structured error code.
        String code = "http_" + status.value();
        String message = body;
        try {
            JsonNode errJson = json.readTree(body);
            String twilioCode = nullableText(errJson.get("code"));
            String twilioMsg = nullableText(errJson.get("message"));
            if (twilioCode != null) code = "twilio_" + twilioCode;
            if (twilioMsg != null) message = twilioMsg;
        } catch (Exception ignored) {
            // body wasn't JSON — fall through with the raw status code
        }
        return new SenderRegistrationResult.Rejected(code, message);
    }

    private JsonNode post(String url, Object body, ProviderCreds creds, String idempotencyKey) {
        return buildClient(creds).post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(HTTP_TIMEOUT);
    }

    private JsonNode postJson(String url, Object body, ProviderCreds creds) {
        return buildClient(creds).post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(HTTP_TIMEOUT);
    }

    private JsonNode get(String url, ProviderCreds creds) {
        return buildClient(creds).get()
                .uri(url)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(HTTP_TIMEOUT);
    }

    private WebClient buildClient(ProviderCreds creds) {
        if (!(creds instanceof TwilioCreds tc)) {
            throw new IllegalArgumentException(
                    "expected TwilioCreds but got " + creds.getClass().getSimpleName());
        }
        String basicAuth = tc.basicAuthHeader();
        return webClientBuilder
                .clone()
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private String messagingUrl(String path) {
        return props.effectiveMessagingBaseUrl() + path;
    }

    private static void ensureCreds(ProviderCreds creds) {
        if (creds == null) {
            throw new IllegalArgumentException("ProviderCreds required");
        }
        if (creds.provider() != ProviderId.TWILIO) {
            throw new IllegalArgumentException(
                    "TwilioMessagingChannelProvider received creds for "
                            + creds.provider().value());
        }
    }

    private static void ensureSameProvider(SenderRef ref) {
        if (ref.provider() != ProviderId.TWILIO) {
            throw new IllegalArgumentException(
                    "SenderRef belongs to " + ref.provider().value() + ", not Twilio");
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null) return fallback;
        JsonNode f = node.get(field);
        return f == null || f.isNull() ? fallback : f.asText(fallback);
    }

    private static String textOrThrow(JsonNode node, String field, String msg) {
        String v = text(node, field, null);
        if (v == null || v.isBlank()) throw new TwilioApiException("twilio_bad_response", msg);
        return v;
    }

    private static String nullableText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String s = node.asText(null);
        return s == null || s.isBlank() ? null : s;
    }

    private static String maskPhone(String e164) {
        if (e164 == null || e164.length() < 7) return "***";
        return e164.substring(0, 3) + "****" + e164.substring(e164.length() - 4);
    }

    private static OutboundResult notWiredYet(String reason) {
        return new OutboundResult.ProviderFailure("not_wired", reason, false);
    }

    /** Thrown when Twilio returns an unrecoverable error on a synchronous
     *  call (revoke). Async paths (register, sync) return typed result
     *  sealed-type variants instead. */
    public static class TwilioApiException extends RuntimeException {
        private final String code;
        public TwilioApiException(String code, String msg) { super(msg); this.code = code; }
        public String code() { return code; }
    }
}
