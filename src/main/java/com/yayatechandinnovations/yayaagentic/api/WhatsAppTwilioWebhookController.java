package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.InboundValidation;
import com.yayatechandinnovations.yayaagentic.channel.MessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.channel.ProviderId;
import com.yayatechandinnovations.yayaagentic.channel.RawInboundRequest;
import com.yayatechandinnovations.yayaagentic.channel.SenderRef;
import com.yayatechandinnovations.yayaagentic.channel.SenderTrustMaterial;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.ChannelMetrics;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.ChannelRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.ChannelTracing;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.InboundDispatchWorker;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioMessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Public-internet edge for Twilio's WhatsApp webhook + status callback. The
 * trust principle (§2 corollary 2) forbids any LLM round-trip until the
 * signature has been verified — the validator runs FIRST, then dedup, then
 * persist, then ack 200 within Twilio's 15 s budget (§9.1). The dispatch
 * worker carries the rest out-of-band.
 *
 * <p>This controller is NOT under {@code /v1/admin/*} — it's a tenant-less,
 * platform-tier surface dialed by Twilio directly.
 */
@RestController
@RequestMapping("/v1/channels/whatsapp/twilio")
public class WhatsAppTwilioWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppTwilioWebhookController.class);

    private final WhatsAppSenderService senders;
    private final TwilioMessagingChannelProvider provider;
    private final InboundMessageRepository inbound;
    private final OutboundMessageRepository outbound;
    private final InboundDispatchWorker worker;
    private final ChannelMetrics metrics;
    private final ChannelRateLimiter rateLimiter;
    private final ChannelTracing tracing;
    private final ObjectMapper json;

    public WhatsAppTwilioWebhookController(WhatsAppSenderService senders,
                                           TwilioMessagingChannelProvider provider,
                                           InboundMessageRepository inbound,
                                           OutboundMessageRepository outbound,
                                           InboundDispatchWorker worker,
                                           ChannelMetrics metrics,
                                           ChannelRateLimiter rateLimiter,
                                           ChannelTracing tracing,
                                           ObjectMapper json) {
        this.senders = senders;
        this.provider = provider;
        this.inbound = inbound;
        this.outbound = outbound;
        this.worker = worker;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
        this.tracing = tracing;
        this.json = json;
    }

    @PostMapping(value = "/webhook/{senderId}",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<ResponseEntity<Void>> webhook(@PathVariable("senderId") String senderIdSegment,
                                              ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> Mono.fromCallable(() ->
                handle(senderIdSegment, exchange, form)));
    }

    @PostMapping(value = "/status-callback/{senderId}",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<ResponseEntity<Void>> statusCallback(@PathVariable("senderId") String senderIdSegment,
                                                     ServerWebExchange exchange) {
        // Status callbacks land in the same signature-verification flow but
        // never enter the worker — they update outbound_messages (W5).
        // For now we verify + 200; the full status-callback handler ships
        // alongside the outbound dispatcher.
        return exchange.getFormData().flatMap(form -> Mono.fromCallable(() ->
                handleStatusCallback(senderIdSegment, exchange, form)));
    }

    @Transactional
    protected ResponseEntity<Void> handle(String senderIdSegment,
                                          ServerWebExchange exchange,
                                          MultiValueMap<String, String> form) {
        return tracing.observeCall("channel.whatsapp.inbound",
                () -> handleInner(senderIdSegment, exchange, form),
                "channel", "whatsapp",
                "sender_id_hint", senderIdSegment);
    }

    private ResponseEntity<Void> handleInner(String senderIdSegment,
                                             ServerWebExchange exchange,
                                             MultiValueMap<String, String> form) {
        // §14.1 path segment is a routing hint — we still derive sender by
        // the To= field and verify via the trust material. A wrong-sender
        // hint falls through and the controller 404s without doing LLM work.
        WhatsAppSender sender = senders.requireForTenant(
                /* tenant lookup */ tenantOf(senderIdSegment), new SenderId(senderIdSegment));

        // §16 rate limit — per-sender token bucket. Stops one tenant's
        // viral abuse from saturating the engine for everyone.
        if (!rateLimiter.allowSender(sender.id().value())) {
            metrics.rateLimited(sender.tenant().value(), sender.id().value(), "sender");
            return ResponseEntity.status(429).build();
        }

        RawInboundRequest req = buildRawRequest(exchange, form);
        SenderTrustMaterial trust = new SenderTrustMaterial(
                new SenderRef(ProviderId.TWILIO,
                        sender.onboarding().twilioSenderSid() == null
                                ? sender.id().value()
                                : sender.onboarding().twilioSenderSid()),
                sender.webhookSecret() == null ? "unused" : sender.webhookSecret());

        InboundValidation validation = provider.validateInbound(req, trust);
        if (validation instanceof InboundValidation.Invalid bad) {
            log.warn("WhatsApp webhook signature rejection sender={} code={} ({})",
                    sender.id().value(), bad.code(), bad.message());
            metrics.signatureFailure(sender.tenant().value(), sender.id().value());
            metrics.inbound(sender.tenant().value(), sender.id().value(),
                    "whatsapp", "signature_rejected");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // §16 + §9 step 3 — dedup by MessageSid. Concurrent retries collapse
        // on the PK violation.
        String messageSid = form.getFirst("MessageSid");
        if (messageSid == null || messageSid.isBlank()) {
            log.warn("WhatsApp webhook missing MessageSid for sender={}", sender.id().value());
            return ResponseEntity.badRequest().build();
        }
        if (inbound.existsById(messageSid)) {
            // Already processed (or queued); idempotent ack.
            metrics.inbound(sender.tenant().value(), sender.id().value(),
                    "whatsapp", "duplicate");
            return ResponseEntity.ok().build();
        }

        String from = stripWhatsAppPrefix(form.getFirst("From"));
        if (from == null) {
            log.warn("WhatsApp webhook missing From for sender={} sid={}",
                    sender.id().value(), messageSid);
            return ResponseEntity.badRequest().build();
        }
        if (!rateLimiter.allowEndUser(sender.id().value(), from)) {
            metrics.rateLimited(sender.tenant().value(), sender.id().value(), "end_user");
            metrics.inbound(sender.tenant().value(), sender.id().value(),
                    "whatsapp", "end_user_rate_limited");
            return ResponseEntity.ok().build();
        }
        int numMedia = parseInt(form.getFirst("NumMedia"), 0);

        Map<String, Object> raw = new LinkedHashMap<>();
        form.forEach((k, vs) -> raw.put(k, vs.isEmpty() ? "" : vs.get(0)));

        try {
            InboundMessageEntity row = new InboundMessageEntity(
                    messageSid,
                    sender.tenant().value(),
                    sender.id().value(),
                    from,
                    numMedia,
                    writeJson(raw));
            inbound.save(row);
        } catch (DataIntegrityViolationException dup) {
            // Lost the dedup race — fine, ack.
            return ResponseEntity.ok().build();
        }

        worker.schedule(messageSid);
        metrics.inbound(sender.tenant().value(), sender.id().value(),
                "whatsapp", "enqueued");
        return ResponseEntity.ok().build();
    }

    protected ResponseEntity<Void> handleStatusCallback(String senderIdSegment,
                                                        ServerWebExchange exchange,
                                                        MultiValueMap<String, String> form) {
        // Signature verify only — actual status-row update is W5 territory.
        WhatsAppSender sender;
        try {
            sender = senders.requireForTenant(
                    tenantOf(senderIdSegment), new SenderId(senderIdSegment));
        } catch (AdminApiException notFound) {
            return ResponseEntity.notFound().build();
        }
        RawInboundRequest req = buildRawRequest(exchange, form);
        SenderTrustMaterial trust = new SenderTrustMaterial(
                new SenderRef(ProviderId.TWILIO,
                        sender.onboarding().twilioSenderSid() == null
                                ? sender.id().value()
                                : sender.onboarding().twilioSenderSid()),
                sender.webhookSecret() == null ? "unused" : sender.webhookSecret());
        InboundValidation validation = provider.validateInbound(req, trust);
        if (validation instanceof InboundValidation.Invalid bad) {
            log.warn("WhatsApp status-callback signature rejection sender={} code={} ({})",
                    sender.id().value(), bad.code(), bad.message());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        applyStatusUpdate(form);
        return ResponseEntity.ok().build();
    }

    @Transactional
    protected void applyStatusUpdate(MultiValueMap<String, String> form) {
        String messageSid = form.getFirst("MessageSid");
        String statusRaw = form.getFirst("MessageStatus");
        if (messageSid == null || statusRaw == null) return;

        outbound.findById(messageSid).ifPresent(row -> {
            String status = mapTwilioStatus(statusRaw);
            row.setStatus(status);
            if ("DELIVERED".equals(status) || "READ".equals(status)) {
                row.setDeliveredAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
            }
            if ("FAILED".equals(status) || "UNDELIVERED".equals(status)) {
                row.setFailedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
                row.setFailureCode(form.getFirst("ErrorCode"));
                row.setFailureMessage(form.getFirst("ErrorMessage"));
            }
            outbound.save(row);
        });
    }

    /** Twilio's WhatsApp status verbiage → our normalized status enum. */
    private static String mapTwilioStatus(String raw) {
        return switch (raw.toLowerCase()) {
            case "queued", "accepted", "scheduled"          -> "QUEUED";
            case "sent", "sending"                          -> "SENT";
            case "delivered"                                -> "DELIVERED";
            case "read"                                     -> "READ";
            case "failed", "canceled"                       -> "FAILED";
            case "undelivered"                              -> "UNDELIVERED";
            default                                          -> raw.toUpperCase();
        };
    }

    /* ---------------------------- helpers --------------------------------- */

    private Ids.TenantId tenantOf(String senderIdSegment) {
        // Look up via the WhatsApp sender service — it scopes by tenant
        // internally. We need the tenant id before we can call the
        // tenant-scoped require; do a cross-tenant find. Avoid a separate
        // method by using the service's listing API via the repository
        // pattern; we use a direct lookup helper.
        return senders.findTenantForSenderId(new SenderId(senderIdSegment))
                .orElseThrow(() -> AdminApiException.notFound("sender_not_found",
                        "no such sender: " + senderIdSegment));
    }

    private RawInboundRequest buildRawRequest(ServerWebExchange exchange,
                                              MultiValueMap<String, String> form) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequest().getHeaders().forEach((k, vs) ->
                headers.put(k.toLowerCase(), vs.isEmpty() ? "" : vs.get(0)));
        Map<String, String> params = new LinkedHashMap<>();
        form.forEach((k, vs) -> params.put(k, vs.isEmpty() ? "" : vs.get(0)));
        return new RawInboundRequest(
                Optional.ofNullable(exchange.getRequest().getPath().pathWithinApplication().value())
                        .orElse(""),
                exchange.getRequest().getMethod().name(),
                exchange.getRequest().getPath().value(),
                headers,
                params,
                null);
    }

    private static String stripWhatsAppPrefix(String from) {
        if (from == null) return null;
        return from.startsWith("whatsapp:") ? from.substring("whatsapp:".length()) : from;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("could not encode webhook payload", e);
        }
    }
}
