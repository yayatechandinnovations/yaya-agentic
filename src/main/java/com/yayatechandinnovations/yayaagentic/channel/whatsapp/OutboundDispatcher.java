package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.InteractivePayload;
import com.yayatechandinnovations.yayaagentic.channel.InteractiveRenderer;
import com.yayatechandinnovations.yayaagentic.channel.OutboundFreeform;
import com.yayatechandinnovations.yayaagentic.channel.OutboundMedia;
import com.yayatechandinnovations.yayaagentic.channel.OutboundResult;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.SenderRef;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundOutboundSink;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundSenders;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioMessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaTokenSigner;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundPendingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundPendingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

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
 * Buffers the engine's {@code Flux<TurnEvent>} into a finalized message and
 * dispatches it through the BSP. Service-window aware (§10.2): if the user
 * hasn't messaged us in 24h, the reply lands in {@code outbound_pending}
 * and releases on their next inbound. Long bodies split on whitespace
 * boundaries to fit WhatsApp's 1600-char per-message limit (§18 risk row).
 *
 * <p>See {@code whatsapp-channel-design.md} §10.
 */
@Component
public class OutboundDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboundDispatcher.class);

    /** WhatsApp's per-message text cap. */
    public static final int MAX_BODY_CHARS = 1600;

    /** Minimum repeated-unit length for {@link #collapseRepeatedBody} to act —
     *  long enough that an exact k-fold repetition is a loop artifact, never a
     *  legitimately repetitive short phrase. */
    static final int MIN_DEDUP_UNIT = 40;

    /** Default buffer guardrail when the engine takes too long. */
    private static final Duration MAX_BUFFER = Duration.ofSeconds(45);

    /** Remembers which hint a parked interactive payload came from. */
    private static final String PARKED_KIND_KEY = "_hintKind";

    /** WhatsApp's 24h customer-service window. */
    private static final Duration SERVICE_WINDOW = Duration.ofHours(24);

    private final TwilioMessagingChannelProvider twilio;
    private final ProviderCredsResolver credsResolver;
    private final InteractiveRenderer renderer;
    private final InboundMessageRepository inbound;
    private final OutboundMessageRepository outbound;
    private final OutboundPendingRepository pending;
    private final WhatsAppSenderService senders;
    private final OptOutService optOuts;
    private final ChannelMetrics metrics;
    private final PlaygroundOutboundSink playgroundSink;
    private final com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground
            .PlaygroundSessionRegistry playgroundSessions;
    private final ObjectMapper json;
    private final MediaTokenSigner mediaSigner;
    private final String publicBaseUrl;
    private final InSessionContents inSession;

    public OutboundDispatcher(TwilioMessagingChannelProvider twilio,
                              ProviderCredsResolver credsResolver,
                              InteractiveRenderer renderer,
                              InboundMessageRepository inbound,
                              OutboundMessageRepository outbound,
                              OutboundPendingRepository pending,
                              WhatsAppSenderService senders,
                              OptOutService optOuts,
                              ChannelMetrics metrics,
                              PlaygroundOutboundSink playgroundSink,
                              com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground
                                      .PlaygroundSessionRegistry playgroundSessions,
                              ObjectMapper json,
                              MediaTokenSigner mediaSigner,
                              @Value("${yaya.agentic.channels.twilio.webhook-base-url:${yaya.agentic.public-base-url:}}")
                              String publicBaseUrl,
                              InSessionContents inSession) {
        this.twilio = twilio;
        this.credsResolver = credsResolver;
        this.renderer = renderer;
        this.inbound = inbound;
        this.outbound = outbound;
        this.pending = pending;
        this.senders = senders;
        this.optOuts = optOuts;
        this.metrics = metrics;
        this.playgroundSink = playgroundSink;
        this.playgroundSessions = playgroundSessions;
        this.json = json;
        this.mediaSigner = mediaSigner;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
        this.inSession = inSession;
    }

    /**
     * Drain the engine's {@code Flux<TurnEvent>}, build an envelope, dispatch.
     * Called by {@link InboundDispatchWorker} after every {@code engine.send}.
     */
    public void dispatchTurn(Ids.TenantId tenant,
                             SenderId sender,
                             String endUserPhone,
                             Ids.SessionId sessionId,
                             Flux<TurnEvent> events) {
        Buffer buffer = new Buffer();
        events
                .doOnNext(buffer::accept)
                .blockLast(MAX_BUFFER);

        if (buffer.body.length() == 0 && buffer.lastHint == null && buffer.attachments.isEmpty()) {
            // Engine produced nothing dispatchable — silent no-op. The
            // recorder still saw every TurnEvent through its own subscription.
            return;
        }
        if (buffer.body.length() > 0 || buffer.lastHint != null) {
            dispatch(tenant, sender, endUserPhone, sessionId,
                    collapseRepeatedBody(buffer.body.toString()), buffer.lastHint);
        }
        if (!buffer.attachments.isEmpty()) {
            dispatchAttachments(tenant, sender, endUserPhone, sessionId, buffer.attachments);
        }
    }

    /**
     * Collapse a body that is an exact whole-number repetition of one block
     * back to a single copy. The agentic loop can re-emit the same narration
     * across rounds (e.g. a denied tool the model re-tries); the engine-side
     * guard caps the loop, and this collapses any residual exact duplication so
     * the user never sees a doubled/tripled message. Conservative by design:
     * acts only on an EXACT k&gt;=2 repetition of a unit &ge; {@link #MIN_DEDUP_UNIT}
     * chars — never near-duplicates — so it cannot mangle legitimately
     * repetitive prose. Package-private for test.
     */
    static String collapseRepeatedBody(String body) {
        if (body == null) return null;
        String s = body.strip();
        int n = s.length();
        if (n < 2 * MIN_DEDUP_UNIT) return body;
        // KMP failure function → smallest period. If the whole string is that
        // period repeated an integer number of times, it's a pure repetition.
        int[] f = new int[n];
        for (int i = 1; i < n; i++) {
            int j = f[i - 1];
            while (j > 0 && s.charAt(i) != s.charAt(j)) j = f[j - 1];
            if (s.charAt(i) == s.charAt(j)) j++;
            f[i] = j;
        }
        int period = n - f[n - 1];
        if (period < n && n % period == 0 && period >= MIN_DEDUP_UNIT) {
            return s.substring(0, period).strip();
        }
        return body;
    }

    /**
     * Same as {@link #dispatchTurn}, but for the greeting + quick-replies
     * the engine returns synchronously from {@code engine.start}.
     */
    public void dispatchGreeting(Ids.TenantId tenant,
                                 SenderId sender,
                                 String endUserPhone,
                                 Ids.SessionId sessionId,
                                 String greeting,
                                 List<String> quickReplies) {
        if (greeting == null || greeting.isBlank()) return;
        TurnEvent.UiHint hint = null;
        if (quickReplies != null && !quickReplies.isEmpty()) {
            List<Map<String, Object>> options = new ArrayList<>();
            for (int i = 0; i < quickReplies.size(); i++) {
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("id", "qr_" + i);
                opt.put("label", quickReplies.get(i));
                options.add(opt);
            }
            hint = new TurnEvent.UiHint("quick_replies", Map.of("options", options));
        }
        dispatch(tenant, sender, endUserPhone, sessionId, greeting, hint);
    }

    /**
     * Release every parked outbound for the (sender, end_user). Called by the
     * {@link InboundDispatchWorker} BEFORE the engine runs on a new inbound
     * — by §10.2 the next inbound from the same end-user reopens the
     * 24h window, so the parked queue can fire immediately.
     */
    @Transactional
    public void releasePending(Ids.TenantId tenant,
                               SenderId sender,
                               String endUserPhone) {
        List<OutboundPendingEntity> parked =
                pending.findByTenantIdAndSenderIdAndEndUserPhoneAndReleasedAtIsNullOrderByQueuedAtAsc(
                        tenant.value(), sender.value(), endUserPhone);
        if (parked.isEmpty()) return;
        for (OutboundPendingEntity row : parked) {
            try {
                TurnEvent.UiHint hint = null;
                if (row.getInteractiveJson() != null && !row.getInteractiveJson().isBlank()) {
                    Map<String, Object> payload = new LinkedHashMap<>(json.readValue(
                            row.getInteractiveJson(),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                    Object kind = payload.remove(PARKED_KIND_KEY);
                    hint = new TurnEvent.UiHint(kind == null ? "quick_replies" : String.valueOf(kind), payload);
                }
                sendNow(tenant, sender, endUserPhone,
                        new Ids.SessionId(row.getSessionId()),
                        row.getBody(), hint);
                row.setReleasedAt(OffsetDateTime.now(ZoneOffset.UTC));
                pending.save(row);
            } catch (Exception e) {
                log.warn("Failed to release pending outbound {} for {}: {}",
                        row.getId(), endUserPhone, e.getMessage());
            }
        }
    }

    /* ---------------------------- core dispatch --------------------------- */

    @Transactional
    protected void dispatch(Ids.TenantId tenant, SenderId sender, String endUserPhone,
                            Ids.SessionId sessionId, String body, TurnEvent.UiHint hint) {
        // §11.2 — never message an opted-out user. This MUST be the first
        // check so a §10 service-window-park doesn't accidentally store an
        // unsendable message either.
        if (optOuts.isOptedOut(sender, endUserPhone)) {
            log.info("Outbound dropped — {} is opted out from sender {}",
                    endUserPhone, sender.value());
            metrics.optedOutBlocked(tenant.value(), sender.value());
            return;
        }
        if (!isInsideServiceWindow(sender.value(), endUserPhone)) {
            park(tenant, sender, endUserPhone, sessionId, body, hint);
            metrics.serviceWindowBlocked(tenant.value(), sender.value());
            return;
        }
        sendNow(tenant, sender, endUserPhone, sessionId, body, hint);
    }

    private void sendNow(Ids.TenantId tenant, SenderId sender, String endUserPhone,
                         Ids.SessionId sessionId, String body, TurnEvent.UiHint hint) {
        // Playground simulator path — never touches Twilio, drops to the
        // in-memory sink the admin UI polls. Skips credsResolver too so the
        // platform stays usable for developer onboarding without Twilio.
        if (PlaygroundSenders.isPlayground(sender.value())) {
            InteractivePayload payload = renderer.render(body, hint,
                    new InteractiveRenderer.RenderContext(new ChannelKind.WhatsApp(), null));
            String renderedBody = payloadBody(payload, body);
            // The engine session id is an internal UUID the playground UI can't
            // know; route the reply to the client-chosen id it actually polls.
            Ids.SessionId sinkKey = playgroundSessions.clientFor(sessionId)
                    .map(Ids.SessionId::new)
                    .orElse(sessionId);
            playgroundSink.append(sinkKey, renderedBody, payload);
            metrics.outbound(tenant.value(), sender.value(), "freeform", "playground");
            return;
        }
        if (!credsResolver.isReady()) {
            log.warn("Outbound skipped for {} — Twilio not configured on this instance",
                    endUserPhone);
            return;
        }
        WhatsAppSender resolved = senders.requireForTenant(tenant, sender);
        if (!resolved.status().acceptsOutbound()) {
            log.warn("Sender {} not accepting outbound (status={}), dropping reply", sender.value(),
                    resolved.status());
            return;
        }
        SenderRef ref = new SenderRef(twilio.id(),
                resolved.onboarding().twilioSenderSid() == null
                        ? resolved.id().value()
                        : resolved.onboarding().twilioSenderSid());

        InteractivePayload payload = renderer.render(body, hint,
                new InteractiveRenderer.RenderContext(new ChannelKind.WhatsApp(), null));

        if (payload instanceof InteractivePayload.Buttons buttons
                && sendNativeButtons(tenant, sender, endUserPhone, sessionId, resolved, buttons)) {
            return;
        }

        List<String> chunks = splitIntoChunks(payloadBody(payload, body), MAX_BODY_CHARS);
        if (chunks.size() > 1) {
            metrics.splitOutbound(tenant.value(), sender.value(), chunks.size());
        }
        for (int i = 0; i < chunks.size(); i++) {
            String chunkBody = i < chunks.size() - 1
                    ? chunks.get(i) + " (…)"
                    : chunks.get(i);

            Map<String, Object> providerHints = new LinkedHashMap<>();
            providerHints.put("fromPhoneE164", resolved.phoneE164());

            OutboundFreeform freeform = new OutboundFreeform(
                    endUserPhone, chunkBody,
                    Optional.empty(),                       // native interactive: W6+ via Content API
                    providerHints);
            OutboundResult result = twilio.sendFreeform(ref, freeform, credsResolver.resolve(resolved));
            recordResult(tenant, sender, endUserPhone, sessionId, chunkBody, result);
            String resultLabel = switch (result) {
                case OutboundResult.Sent s            -> "sent";
                case OutboundResult.OutsideServiceWindow o -> "outside_window";
                case OutboundResult.TemplateRequired t   -> "template_required";
                case OutboundResult.OptedOut oo            -> "opted_out";
                case OutboundResult.ProviderFailure p   -> "provider_failure";
            };
            metrics.outbound(tenant.value(), sender.value(), "freeform", resultLabel);
        }

        // For multi-chunk sends, the interactive payload (if any) was
        // rendered as numbered text into the last chunk by payloadBody.
        // No additional dispatch step needed.
    }

    /**
     * Real WhatsApp quick-reply buttons through a generic in-session Content
     * resource (GAP-W3). False when that path is unavailable, so the caller
     * falls back to numbered text — a reply is never lost to a Content error.
     */
    private boolean sendNativeButtons(Ids.TenantId tenant, SenderId sender, String endUserPhone,
                                      Ids.SessionId sessionId, WhatsAppSender resolved,
                                      InteractivePayload.Buttons buttons) {
        String text = buttons.body() == null ? "" : buttons.body();
        if (buttons.footer() != null && !buttons.footer().isBlank()) text = text + "\n\n" + buttons.footer();
        if (text.isBlank() || text.length() > InSessionContents.MAX_BODY_CHARS
                || buttons.buttons().size() > InSessionContents.MAX_BUTTONS) {
            return false;
        }
        var creds = credsResolver.resolve(resolved);
        Optional<String> contentSid = inSession.quickReply(buttons.buttons().size(), creds);
        if (contentSid.isEmpty()) return false;
        List<String[]> titled = buttons.buttons().stream()
                .map(b -> new String[]{b.title(), b.id()}).toList();
        OutboundResult result = twilio.sendContent(endUserPhone, contentSid.get(),
                InSessionContents.quickReplyVariables(text, titled), providerHints(resolved), creds);
        if (!(result instanceof OutboundResult.Sent)) {
            log.warn("Native buttons failed for {} ({}); falling back to numbered text", endUserPhone, result);
            return false;
        }
        recordResult(tenant, sender, endUserPhone, sessionId, text, result);
        metrics.outbound(tenant.value(), sender.value(), "interactive", "sent");
        return true;
    }

    private Map<String, Object> providerHints(WhatsAppSender resolved) {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("fromPhoneE164", resolved.phoneE164());
        if (!publicBaseUrl.isBlank()) {
            hints.put("statusCallbackUrl", publicBaseUrl + STATUS_CALLBACK_PATH + resolved.id().value());
        }
        return hints;
    }

    private static final String STATUS_CALLBACK_PATH = "/v1/channels/whatsapp/twilio/status-callback/";

    private String payloadBody(InteractivePayload payload, String fallbackBody) {
        return switch (payload) {
            case InteractivePayload.PlainText pt -> pt.body();
            case InteractivePayload.Buttons b -> renderInteractiveAsText(b.body(),
                    b.buttons().stream().map(InteractivePayload.Button::title).toList(),
                    b.footer());
            case InteractivePayload.ListMessage lm -> renderInteractiveAsText(lm.body(),
                    lm.sections().stream()
                            .flatMap(s -> s.rows().stream())
                            .map(InteractivePayload.Row::title)
                            .toList(),
                    lm.footer());
        };
    }

    /** When the BSP path doesn't support native interactive (free-form +
     *  pre-Content-API), the dispatcher inlines the options as numbered
     *  text. The {@link DefaultInteractiveRenderer} also publishes the
     *  structured payload — once {@code sendFreeform} grows native support
     *  in W6+, the dispatcher will pass it through instead of flattening. */
    private String renderInteractiveAsText(String body, List<String> labels, String footer) {
        StringBuilder sb = new StringBuilder(body == null ? "" : body);
        if (!labels.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            for (int i = 0; i < labels.size(); i++) {
                sb.append(i + 1).append(". ").append(labels.get(i));
                if (i < labels.size() - 1) sb.append("\n");
            }
        }
        if (footer != null && !footer.isBlank()) {
            sb.append("\n\n").append(footer);
        }
        return sb.toString();
    }

    /** Greedy whitespace-boundary chunker. */
    static List<String> splitIntoChunks(String body, int max) {
        if (body == null || body.length() <= max) {
            return List.of(body == null ? "" : body);
        }
        List<String> out = new ArrayList<>();
        int idx = 0;
        while (idx < body.length()) {
            int end = Math.min(body.length(), idx + max);
            if (end < body.length()) {
                int lastSpace = body.lastIndexOf(' ', end);
                if (lastSpace > idx + max / 2) end = lastSpace;
            }
            out.add(body.substring(idx, end).trim());
            idx = end;
            while (idx < body.length() && body.charAt(idx) == ' ') idx++;
        }
        return out;
    }

    private boolean isInsideServiceWindow(String senderId, String endUserPhone) {
        Instant cutoff = Instant.now().minus(SERVICE_WINDOW);
        // Use the (sender_id, end_user_phone, received_at DESC) index. The
        // repository's natural ordering is on received_at DESC so the head
        // is the latest inbound from this user.
        return inbound.findLatestForSenderAndEndUserSince(senderId, endUserPhone,
                OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC)).isPresent();
    }

    @Transactional
    protected void park(Ids.TenantId tenant, SenderId sender, String endUserPhone,
                        Ids.SessionId sessionId, String body, TurnEvent.UiHint hint) {
        String interactive = null;
        if (hint != null && hint.payload() != null) {
            try {
                Map<String, Object> stored = new LinkedHashMap<>(hint.payload());
                stored.put(PARKED_KIND_KEY, hint.kind());
                interactive = json.writeValueAsString(stored);
            } catch (JsonProcessingException e) {
                log.warn("Failed to encode interactive payload for parked outbound: {}", e.getMessage());
            }
        }
        OutboundPendingEntity row = new OutboundPendingEntity(
                tenant.value(), sender.value(), endUserPhone, sessionId.value(),
                body, interactive);
        pending.save(row);
        log.info("Outbound parked for {} (outside 24h service window)", endUserPhone);
    }

    private void recordResult(Ids.TenantId tenant, SenderId sender, String endUserPhone,
                              Ids.SessionId sessionId, String body, OutboundResult result) {
        if (result instanceof OutboundResult.Sent sent) {
            OutboundMessageEntity row = new OutboundMessageEntity(
                    sent.messageSid(),
                    tenant.value(),
                    sender.value(),
                    endUserPhone,
                    sessionId == null ? null : sessionId.value(),
                    body);
            row.setStatus("SENT");
            outbound.save(row);
        } else if (result instanceof OutboundResult.ProviderFailure f) {
            log.warn("Outbound dispatch failure for {} (retriable={}) code={}: {}",
                    endUserPhone, f.retriable(), f.code(), f.message());
            // §10.3 — persist the failed send so the retry job can pick it
            // back up. A synthetic sid is fine here; Twilio's real sid only
            // exists when a send actually succeeds.
            String pendingSid = "PNDF_" + java.util.UUID.randomUUID();
            OutboundMessageEntity row = new OutboundMessageEntity(
                    pendingSid, tenant.value(), sender.value(), endUserPhone,
                    sessionId == null ? null : sessionId.value(), body);
            row.setStatus(f.retriable() ? "QUEUED" : "FAILED");
            row.setFailureCode(f.code());
            row.setFailureMessage(f.message());
            row.setRetryCount(0);
            if (!f.retriable()) {
                row.setFailedAt(OffsetDateTime.now(ZoneOffset.UTC));
            }
            outbound.save(row);
        }
    }

    /** Re-dispatch a previously failed send. Called by the retry job. Returns
     *  the {@link OutboundResult} so the job can update state. */
    public OutboundResult resend(OutboundMessageEntity row) {
        if (!credsResolver.isReady()) {
            return new OutboundResult.ProviderFailure(
                    "provider_not_ready", "Twilio not configured", true);
        }
        WhatsAppSender resolved = senders.requireForTenant(
                new Ids.TenantId(row.getTenantId()),
                new SenderId(row.getSenderId()));
        SenderRef ref = new SenderRef(twilio.id(),
                resolved.onboarding().twilioSenderSid() == null
                        ? resolved.id().value()
                        : resolved.onboarding().twilioSenderSid());
        Map<String, Object> providerHints = new LinkedHashMap<>();
        providerHints.put("fromPhoneE164", resolved.phoneE164());
        OutboundFreeform freeform = new OutboundFreeform(
                row.getEndUserPhone(), row.getBody(),
                Optional.empty(),
                providerHints);
        return twilio.sendFreeform(ref, freeform, credsResolver.resolve(resolved));
    }

    /** Simple accumulator over a {@code Flux<TurnEvent>}. */
    private static final class Buffer {
        final StringBuilder body = new StringBuilder();
        final List<TurnEvent.UiHint> attachments = new ArrayList<>();
        TurnEvent.UiHint lastHint;

        void accept(TurnEvent event) {
            if (event instanceof TurnEvent.Token t) {
                body.append(t.text());
            } else if (event instanceof TurnEvent.UiHint h) {
                // "attachment" hints (engine §6.3 media outputs) accumulate —
                // each becomes its own media message; every other hint kind
                // keeps last-wins semantics (quick_replies, menu, confirm…).
                if ("attachment".equals(h.kind())) {
                    attachments.add(h);
                } else {
                    lastHint = h;
                }
            }
            // Citation events are recorded by the recorder side; the
            // dispatcher doesn't surface them on WhatsApp in v1 (§10.1).
            // ToolCall / ToolResult are internal to the recorder.
        }
    }

    /**
     * WhatsApp leg for engine "attachment" UiHints (a tool's declared media
     * output). Each attachment goes out as its own media message: Twilio
     * fetches the file from a fresh signed {@code /v1/media/{token}} URL, so
     * the platform's public base URL must be set and reachable from Twilio.
     * Service-window rule: media can't be parked (outbound_pending stores
     * text only), so outside the 24h window the attachment is dropped with a
     * log — in practice these are replies to an inbound and always inside it.
     */
    private void dispatchAttachments(Ids.TenantId tenant, SenderId sender, String endUserPhone,
                                     Ids.SessionId sessionId, List<TurnEvent.UiHint> attachments) {
        if (optOuts.isOptedOut(sender, endUserPhone)) {
            metrics.optedOutBlocked(tenant.value(), sender.value());
            return;
        }
        if (PlaygroundSenders.isPlayground(sender.value())) {
            for (TurnEvent.UiHint h : attachments) {
                String filename = String.valueOf(h.payload().getOrDefault("filename", "archivo"));
                Ids.SessionId sinkKey = playgroundSessions.clientFor(sessionId)
                        .map(Ids.SessionId::new)
                        .orElse(sessionId);
                playgroundSink.append(sinkKey, "[attachment] " + filename, null);
            }
            metrics.outbound(tenant.value(), sender.value(), "media", "playground");
            return;
        }
        if (!isInsideServiceWindow(sender.value(), endUserPhone)) {
            log.warn("Dropping {} attachment(s) for {} — outside the 24h service window "
                    + "and outbound_pending is text-only", attachments.size(), endUserPhone);
            metrics.serviceWindowBlocked(tenant.value(), sender.value());
            return;
        }
        if (publicBaseUrl.isBlank()) {
            log.warn("Dropping {} attachment(s) — no public base url configured "
                    + "(yaya.agentic.public-base-url); Twilio cannot fetch relative media links",
                    attachments.size());
            return;
        }
        if (!credsResolver.isReady()) {
            log.warn("Attachments skipped for {} — Twilio not configured on this instance",
                    endUserPhone);
            return;
        }
        WhatsAppSender resolved = senders.requireForTenant(tenant, sender);
        if (!resolved.status().acceptsOutbound()) {
            log.warn("Sender {} not accepting outbound (status={}), dropping attachments",
                    sender.value(), resolved.status());
            return;
        }
        SenderRef ref = new SenderRef(twilio.id(),
                resolved.onboarding().twilioSenderSid() == null
                        ? resolved.id().value()
                        : resolved.onboarding().twilioSenderSid());

        for (TurnEvent.UiHint h : attachments) {
            Map<String, Object> p = h.payload();
            String filename = String.valueOf(p.getOrDefault("filename", "archivo"));
            String contentType = p.get("contentType") == null ? null : String.valueOf(p.get("contentType"));
            // Prefer re-minting a fresh token off the assetId — the hint's own
            // URL was minted at turn time and Twilio's fetch (plus retries)
            // deserves the full 15-minute TTL.
            String path;
            Object assetId = p.get("assetId");
            if (assetId != null && !String.valueOf(assetId).isBlank()) {
                path = "/v1/media/" + mediaSigner.mint(tenant, String.valueOf(assetId));
            } else if (p.get("url") instanceof String u && !u.isBlank()) {
                path = u;
            } else {
                log.warn("Attachment hint without assetId or url — skipped");
                continue;
            }
            String mediaUrl = path.startsWith("http") ? path : publicBaseUrl + path;

            Map<String, Object> providerHints = new LinkedHashMap<>();
            providerHints.put("fromPhoneE164", resolved.phoneE164());
            OutboundMedia media = new OutboundMedia(
                    endUserPhone, mediaUrl, filename, contentType, filename, providerHints);
            OutboundResult result = twilio.sendMedia(ref, media, credsResolver.resolve(resolved));
            recordResult(tenant, sender, endUserPhone, sessionId, "[media] " + filename, result);
            String resultLabel = result instanceof OutboundResult.Sent ? "sent" : "failed";
            if (!(result instanceof OutboundResult.Sent)) {
                log.warn("Attachment send {} for {} → {}: {}", resultLabel, filename,
                        endUserPhone, result);
            }
            metrics.outbound(tenant.value(), sender.value(), "media", resultLabel);
        }
    }
}
