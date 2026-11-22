package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.InteractivePayload;
import com.yayatechandinnovations.yayaagentic.channel.OutboundMedia;
import com.yayatechandinnovations.yayaagentic.channel.OutboundResult;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.SenderRef;
import com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundOutboundSink;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundSenders;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundSessionRegistry;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.WhatsAppTemplate;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.WhatsAppTemplateService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioMessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.outreach.ActionItem;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaTokenSigner;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The WhatsApp leg of proactive (outreach) delivery — GAP-P1 of
 * whatsapp-ops-integration-design.md §6.2. Inside the 24 h service window the
 * message goes out as an in-session card (media header + native buttons, no
 * approval needed); outside it, as the APPROVED template the envelope names,
 * with the media as the template's document header and each button's payload
 * set to its action capability ({@code act:<id>}).
 *
 * <p>Never throws for a delivery problem: every reason the message cannot go
 * out on WhatsApp comes back as a {@code fallbackReason}, and the caller
 * delivers to the chat instead — no silent drops (§6.1).
 *
 * <p>The tenant's Playground simulator is a number like any other: its
 * messages land in the Playground conversation the recipient phone last wrote
 * from, as body + quick replies (the Playground has no 24 h window, so never a
 * template), with the same {@code act:<id>} payloads a real number carries.
 */
@Component
public class WhatsAppOutreachLeg {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppOutreachLeg.class);

    private static final Duration SERVICE_WINDOW = Duration.ofHours(24);
    private static final int MAX_BUTTON_TITLE = 20;
    private static final int MAX_TEMPLATE_PARAM = 120;
    private static final String STATUS_CALLBACK_PATH = "/v1/channels/whatsapp/twilio/status-callback/";

    private final WhatsAppSenderService senders;
    private final OptOutService optOuts;
    private final WhatsAppTemplateService templates;
    private final TwilioMessagingChannelProvider twilio;
    private final ProviderCredsResolver credsResolver;
    private final InSessionContents inSession;
    private final InboundMessageRepository inbound;
    private final OutboundMessageRepository outbound;
    private final MediaTokenSigner mediaSigner;
    private final ChannelMetrics metrics;
    private final PlaygroundOutboundSink playgroundSink;
    private final PlaygroundSessionRegistry playgroundSessions;
    private final String publicBaseUrl;

    public WhatsAppOutreachLeg(WhatsAppSenderService senders,
                               OptOutService optOuts,
                               WhatsAppTemplateService templates,
                               TwilioMessagingChannelProvider twilio,
                               ProviderCredsResolver credsResolver,
                               InSessionContents inSession,
                               InboundMessageRepository inbound,
                               OutboundMessageRepository outbound,
                               MediaTokenSigner mediaSigner,
                               ChannelMetrics metrics,
                               PlaygroundOutboundSink playgroundSink,
                               PlaygroundSessionRegistry playgroundSessions,
                               @Value("${yaya.agentic.channels.twilio.webhook-base-url:${yaya.agentic.public-base-url:}}")
                               String publicBaseUrl) {
        this.senders = senders;
        this.optOuts = optOuts;
        this.templates = templates;
        this.twilio = twilio;
        this.credsResolver = credsResolver;
        this.inSession = inSession;
        this.inbound = inbound;
        this.outbound = outbound;
        this.mediaSigner = mediaSigner;
        this.metrics = metrics;
        this.playgroundSink = playgroundSink;
        this.playgroundSessions = playgroundSessions;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    /** A tenant-owned media asset already resolved by the outreach service. */
    public record Media(String assetId, String filename, String contentType) {}

    public record Request(Ids.TenantId tenant, String whatsappSenderId, String phoneE164, String body,
                          List<Media> media, List<ActionItem> actions, DeliveryRequest.TemplateRef template) {
        public Request {
            media = media == null ? List.of() : List.copyOf(media);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    /**
     * @param deliveredBody what the user actually received (template rendered),
     *                      kept so a later reply can be given its context
     */
    public record Outcome(boolean delivered, String fallbackReason, String providerMessageSid,
                          String templateName, String deliveredBody, String failureCode) {
        static Outcome fallback(String reason) {
            return new Outcome(false, reason, null, null, null, null);
        }

        static Outcome failed(String code) {
            return new Outcome(false, "send_failed", null, null, null, code);
        }
    }

    public Outcome deliver(Request r) {
        if (r.phoneE164() == null || r.phoneE164().isBlank()) return Outcome.fallback("missing_phone");
        if (r.whatsappSenderId() == null || r.whatsappSenderId().isBlank()) {
            return Outcome.fallback("no_whatsapp_sender");
        }
        if (PlaygroundSenders.isPlayground(r.whatsappSenderId())) {
            return deliverToPlayground(r);
        }
        SenderId senderId = new SenderId(r.whatsappSenderId());
        WhatsAppSender sender;
        try {
            sender = senders.requireForTenant(r.tenant(), senderId);
        } catch (RuntimeException e) {
            return Outcome.fallback("no_whatsapp_sender");
        }
        if (!sender.status().acceptsOutbound()) return Outcome.fallback("sender_inactive");
        if (optOuts.isOptedOut(senderId, r.phoneE164())) {
            metrics.optedOutBlocked(r.tenant().value(), senderId.value());
            return Outcome.fallback("opted_out");
        }
        if (!credsResolver.isReady()) return Outcome.fallback("provider_not_configured");

        try {
            var creds = credsResolver.resolve(sender);
            Outcome outcome = insideServiceWindow(senderId.value(), r.phoneE164())
                    ? sendInSession(r, sender, creds)
                    : sendTemplate(r, sender, creds);
            if (outcome.delivered()) {
                outbound.save(sentRow(outcome.providerMessageSid(), r.tenant(), senderId, r.phoneE164(),
                        outcome.deliveredBody()));
            }
            metrics.outbound(r.tenant().value(), senderId.value(), "outreach",
                    outcome.delivered() ? "sent" : outcome.fallbackReason());
            return outcome;
        } catch (RuntimeException e) {
            log.warn("WhatsApp outreach to {} failed: {}", mask(r.phoneE164()), e.getMessage());
            return Outcome.failed("exception");
        }
    }

    /* ----------------------------- playground ----------------------------- */

    private Outcome deliverToPlayground(Request r) {
        SenderId own = PlaygroundSenders.forTenant(r.tenant());
        if (!own.value().equals(r.whatsappSenderId())) return Outcome.fallback("no_whatsapp_sender");
        if (optOuts.isOptedOut(own, r.phoneE164())) {
            metrics.optedOutBlocked(r.tenant().value(), own.value());
            return Outcome.fallback("opted_out");
        }
        Optional<String> conversation = playgroundSessions.clientForPhone(r.tenant(), r.phoneE164());
        if (conversation.isEmpty()) return Outcome.fallback("no_playground_session");

        String body = r.body() == null ? "" : r.body();
        List<InteractivePayload.Button> buttons = new ArrayList<>();
        for (String[] b : buttons(r.actions())) buttons.add(new InteractivePayload.Button(b[1], b[0]));
        InteractivePayload payload = buttons.isEmpty()
                ? new InteractivePayload.PlainText(body)
                : new InteractivePayload.Buttons(body, buttons, null);
        String rendered = OutboundDispatcher.textFallback(payload, null);
        PlaygroundOutboundSink.Message message = playgroundSink.append(
                new Ids.SessionId(conversation.get()), rendered, payload);
        metrics.outbound(r.tenant().value(), own.value(), "outreach", "playground");
        return new Outcome(true, null, "playground:" + message.id(), null, body, null);
    }

    /* ----------------------------- in session ----------------------------- */

    private Outcome sendInSession(Request r, WhatsAppSender sender,
                                  com.yayatechandinnovations.yayaagentic.channel.ProviderCreds creds) {
        Map<String, Object> hints = hints(sender);
        List<String[]> buttons = buttons(r.actions());
        String body = r.body() == null || r.body().isBlank() ? " " : r.body();
        if (body.length() > InSessionContents.MAX_BODY_CHARS) {
            body = body.substring(0, InSessionContents.MAX_BODY_CHARS - 1) + "…";
        }

        // Extra attachments go first, on their own; the first one rides the card.
        for (int i = 1; i < r.media().size(); i++) {
            Media m = r.media().get(i);
            OutboundResult extra = twilio.sendMedia(ref(sender), new OutboundMedia(r.phoneE164(),
                    mediaUrl(r.tenant(), m), m.filename(), m.contentType(), m.filename(), hints), creds);
            if (!(extra instanceof OutboundResult.Sent)) {
                log.warn("Outreach extra attachment {} not sent: {}", m.filename(), extra);
            }
        }

        OutboundResult result = null;
        if (!r.media().isEmpty()) {
            Media first = r.media().get(0);
            Optional<String> card = inSession.mediaCard(buttons.size(), creds);
            if (card.isPresent()) {
                result = twilio.sendContent(r.phoneE164(), card.get(),
                        InSessionContents.mediaCardVariables(body, mediaPath(r.tenant(), first), buttons),
                        hints, creds);
            } else {
                // Without buttons the text is the document's caption: one native message.
                String caption = buttons.isEmpty() ? body : first.filename();
                OutboundResult media = twilio.sendMedia(ref(sender), new OutboundMedia(r.phoneE164(),
                        mediaUrl(r.tenant(), first), caption, first.contentType(), first.filename(),
                        hints), creds);
                if (!(media instanceof OutboundResult.Sent)) return failure(media);
                if (buttons.isEmpty()) result = media;
            }
        }
        if (result == null && !buttons.isEmpty()) {
            Optional<String> qr = inSession.quickReply(buttons.size(), creds);
            if (qr.isPresent()) {
                result = twilio.sendContent(r.phoneE164(), qr.get(),
                        InSessionContents.quickReplyVariables(body, buttons), hints, creds);
            }
        }
        if (result == null) {
            // No buttons (or Content unavailable): plain text. Bound actions
            // stay reachable from the chat inbox; a typed reply still has the
            // message as context (§6.6).
            result = twilio.sendFreeform(ref(sender), new com.yayatechandinnovations.yayaagentic.channel
                    .OutboundFreeform(r.phoneE164(), body, Optional.empty(), hints), creds);
        }
        if (!(result instanceof OutboundResult.Sent sent)) return failure(result);
        return new Outcome(true, null, sent.messageSid(), null, body, null);
    }

    /* ------------------------------ template ------------------------------ */

    private Outcome sendTemplate(Request r, WhatsAppSender sender,
                                 com.yayatechandinnovations.yayaagentic.channel.ProviderCreds creds) {
        DeliveryRequest.TemplateRef ref = r.template();
        if (ref == null) return Outcome.fallback("no_template");
        Optional<WhatsAppTemplate> found = templates.findApproved(r.tenant(), sender,
                ref.name(), ref.language());
        if (found.isEmpty() || found.get().bspContentSid() == null) return Outcome.fallback("template_not_approved");
        WhatsAppTemplate t = found.get();
        boolean mediaHeader = !"NONE".equals(t.headerKind());
        if (mediaHeader && r.media().isEmpty()) return Outcome.fallback("no_template");

        int n = t.placeholderCount();
        Map<String, String> vars = new LinkedHashMap<>();
        List<String> bodyVars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String v = i < ref.variables().size() ? sanitizeParam(ref.variables().get(i)) : "—";
            bodyVars.add(v);
            vars.put(String.valueOf(i + 1), v);
        }
        List<ActionItem> actions = r.actions();
        for (int i = 0; i < t.buttons().size(); i++) {
            String id = i < actions.size() && actions.get(i).actionId() != null
                    ? DefaultInteractiveRenderer.ACTION_PREFIX + actions.get(i).actionId() : "act:none";
            vars.put(String.valueOf(n + i + 1), id);
        }
        if (mediaHeader) {
            vars.put(String.valueOf(n + t.buttons().size() + 1), mediaPath(r.tenant(), r.media().get(0)));
        }
        OutboundResult result = twilio.sendContent(r.phoneE164(), t.bspContentSid(), vars, hints(sender), creds);
        if (!(result instanceof OutboundResult.Sent sent)) return failure(result);
        return new Outcome(true, null, sent.messageSid(), t.name(), renderBody(t.body(), bodyVars), null);
    }

    /**
     * Meta's template-parameter rules: no newline or tab, no run of four or
     * more spaces, bounded length, never empty. Applied again here even though
     * a well-behaved sender already sanitized — a rejected send is a lost
     * notification.
     */
    static String sanitizeParam(String raw) {
        if (raw == null) return "—";
        String s = raw.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {4,}", "   ").strip();
        if (s.length() > MAX_TEMPLATE_PARAM) s = s.substring(0, MAX_TEMPLATE_PARAM - 1).strip() + "…";
        return s.isEmpty() ? "—" : s;
    }

    static String renderBody(String body, List<String> vars) {
        String out = body;
        for (int i = vars.size(); i >= 1; i--) out = out.replace("{{" + i + "}}", vars.get(i - 1));
        return out;
    }

    /* ------------------------------ helpers ------------------------------- */

    private static List<String[]> buttons(List<ActionItem> actions) {
        List<String[]> out = new ArrayList<>();
        for (ActionItem a : actions) {
            if (a.actionId() == null) continue;
            if (out.size() == InSessionContents.MAX_BUTTONS) {
                log.warn("Outreach message has more than {} actions; extra ones stay chat-only",
                        InSessionContents.MAX_BUTTONS);
                break;
            }
            String title = a.label().length() > MAX_BUTTON_TITLE ? a.label().substring(0, MAX_BUTTON_TITLE) : a.label();
            out.add(new String[]{title, DefaultInteractiveRenderer.ACTION_PREFIX + a.actionId()});
        }
        return out;
    }

    /** {@code <token>/<filename>}: the variable tail of a media URL, file suffix included. */
    private String mediaPath(Ids.TenantId tenant, Media m) {
        String name = m.filename() == null || m.filename().isBlank() ? "archivo.pdf"
                : m.filename().replaceAll("[^A-Za-z0-9._-]", "_");
        return mediaSigner.mint(tenant, m.assetId()) + "/" + name;
    }

    private String mediaUrl(Ids.TenantId tenant, Media m) {
        return publicBaseUrl + "/v1/media/" + mediaPath(tenant, m);
    }

    private Map<String, Object> hints(WhatsAppSender sender) {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("fromPhoneE164", sender.phoneE164());
        if (!publicBaseUrl.isBlank()) {
            hints.put("statusCallbackUrl", publicBaseUrl + STATUS_CALLBACK_PATH + sender.id().value());
        }
        return hints;
    }

    private SenderRef ref(WhatsAppSender sender) {
        return new SenderRef(twilio.id(), sender.onboarding().twilioSenderSid() == null
                ? sender.id().value() : sender.onboarding().twilioSenderSid());
    }

    private boolean insideServiceWindow(String senderId, String phone) {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(Instant.now().minus(SERVICE_WINDOW), ZoneOffset.UTC);
        return inbound.findLatestForSenderAndEndUserSince(senderId, phone, cutoff).isPresent();
    }

    private static Outcome failure(OutboundResult result) {
        String code = result instanceof OutboundResult.ProviderFailure f ? f.code() : String.valueOf(result);
        log.warn("WhatsApp outreach send failed: {}", result);
        return Outcome.failed(code);
    }

    private static OutboundMessageEntity sentRow(String sid, Ids.TenantId tenant, SenderId sender,
                                                 String phone, String body) {
        OutboundMessageEntity row = new OutboundMessageEntity(sid, tenant.value(), sender.value(), phone, null, body);
        row.setStatus("SENT");
        return row;
    }

    private static String mask(String e164) {
        if (e164 == null || e164.length() < 7) return "***";
        return e164.substring(0, 3) + "****" + e164.substring(e164.length() - 4);
    }
}
