package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.InboundValidation;
import com.yayatechandinnovations.yayaagentic.channel.ProviderId;
import com.yayatechandinnovations.yayaagentic.channel.RawInboundRequest;
import com.yayatechandinnovations.yayaagentic.channel.SenderRef;
import com.yayatechandinnovations.yayaagentic.channel.SenderTrustMaterial;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsInboundWorker;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSender;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSenderId;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSenderService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioMessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public-internet edge for Twilio's SMS webhook, the SMS twin of
 * {@code WhatsAppTwilioWebhookController}. Signature verification runs FIRST
 * (no LLM round-trip on an unsigned call), then dedup by MessageSid, then
 * persist + ack 200; the {@link SmsInboundWorker} carries the rest out-of-band.
 *
 * <p>Also exposes a property-gated <b>dev simulation</b> endpoint
 * ({@code yaya.agentic.channels.sms.sim-inbound.enabled}, default false) that
 * injects an inbound without a Twilio signature — for local end-to-end testing
 * of the inbound→engine→outbound loop with no Twilio account. It is OFF by
 * default and must never be enabled on an internet-exposed instance.
 */
@RestController
@RequestMapping("/v1/channels/sms")
public class SmsTwilioWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SmsTwilioWebhookController.class);

    private final SmsSenderService senders;
    private final TwilioMessagingChannelProvider provider;
    private final InboundMessageRepository inbound;
    private final SmsInboundWorker worker;
    private final ObjectMapper json;
    private final boolean simEnabled;

    public SmsTwilioWebhookController(SmsSenderService senders,
                                      TwilioMessagingChannelProvider provider,
                                      InboundMessageRepository inbound,
                                      SmsInboundWorker worker,
                                      ObjectMapper json,
                                      @Value("${yaya.agentic.channels.sms.sim-inbound.enabled:false}") boolean simEnabled) {
        this.senders = senders;
        this.provider = provider;
        this.inbound = inbound;
        this.worker = worker;
        this.json = json;
        this.simEnabled = simEnabled;
    }

    /* ---------------------------- real webhook ---------------------------- */

    @PostMapping(value = "/twilio/webhook/{senderId}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<ResponseEntity<Void>> webhook(@PathVariable("senderId") String senderId,
                                              ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> Mono.fromCallable(() -> handle(senderId, exchange, form)));
    }

    @Transactional
    protected ResponseEntity<Void> handle(String senderIdSegment, ServerWebExchange exchange,
                                          MultiValueMap<String, String> form) {
        SmsSender sender;
        try {
            sender = senders.requireForTenant(tenantOf(senderIdSegment), new SmsSenderId(senderIdSegment));
        } catch (AdminApiException notFound) {
            return ResponseEntity.notFound().build();
        }

        SenderTrustMaterial trust = new SenderTrustMaterial(
                new SenderRef(ProviderId.TWILIO,
                        sender.onboarding().twilioSenderSid() == null
                                ? sender.id().value() : sender.onboarding().twilioSenderSid()),
                sender.webhookSecret() == null ? "unused" : sender.webhookSecret());
        InboundValidation validation = provider.validateInbound(buildRaw(exchange, form), trust);
        if (validation instanceof InboundValidation.Invalid bad) {
            log.warn("SMS webhook signature rejection sender={} code={}", sender.id().value(), bad.code());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String messageSid = form.getFirst("MessageSid");
        String from = form.getFirst("From");
        String body = form.getFirst("Body");
        if (messageSid == null || messageSid.isBlank() || from == null) {
            return ResponseEntity.badRequest().build();
        }
        return persistAndSchedule(sender, messageSid, from, body, rawMap(form));
    }

    /* ---------------------------- dev simulation -------------------------- */

    public record SimInboundRequest(String from, String body) {}

    @PostMapping("/sim/{senderId}")
    public ResponseEntity<Map<String, String>> sim(@PathVariable("senderId") String senderIdSegment,
                                                    @RequestBody SimInboundRequest req) {
        if (!simEnabled) {
            throw AdminApiException.forbidden("sim_disabled",
                    "SMS inbound simulation is disabled — set yaya.agentic.channels.sms.sim-inbound.enabled=true (dev only)");
        }
        if (req == null || req.from() == null || req.from().isBlank()) {
            throw AdminApiException.badRequest("missing_from", "from (E.164) required");
        }
        SmsSender sender = senders.requireForTenant(tenantOf(senderIdSegment), new SmsSenderId(senderIdSegment));
        String messageSid = "SMsim" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("MessageSid", messageSid);
        raw.put("From", req.from());
        raw.put("Body", req.body() == null ? "" : req.body());
        persistAndSchedule(sender, messageSid, req.from(), req.body(), raw);
        return ResponseEntity.accepted().body(Map.of("messageSid", messageSid, "status", "accepted"));
    }

    /* ------------------------------ helpers ------------------------------- */

    private ResponseEntity<Void> persistAndSchedule(SmsSender sender, String messageSid,
                                                    String from, String body, Map<String, Object> raw) {
        if (inbound.existsById(messageSid)) {
            return ResponseEntity.ok().build();   // idempotent ack
        }
        try {
            InboundMessageEntity row = new InboundMessageEntity(
                    messageSid, sender.tenant().value(), sender.id().value(), from, 0, writeJson(raw));
            inbound.save(row);
        } catch (DataIntegrityViolationException dup) {
            return ResponseEntity.ok().build();
        }
        worker.schedule(messageSid);
        return ResponseEntity.ok().build();
    }

    private Ids.TenantId tenantOf(String senderIdSegment) {
        return senders.findTenantForSenderId(new SmsSenderId(senderIdSegment))
                .orElseThrow(() -> AdminApiException.notFound("sender_not_found", "no such sender: " + senderIdSegment));
    }

    private RawInboundRequest buildRaw(ServerWebExchange exchange, MultiValueMap<String, String> form) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequest().getHeaders().forEach((k, vs) -> headers.put(k.toLowerCase(), vs.isEmpty() ? "" : vs.get(0)));
        Map<String, String> params = new LinkedHashMap<>();
        form.forEach((k, vs) -> params.put(k, vs.isEmpty() ? "" : vs.get(0)));
        return new RawInboundRequest(
                Optional.ofNullable(exchange.getRequest().getPath().pathWithinApplication().value()).orElse(""),
                exchange.getRequest().getMethod().name(),
                exchange.getRequest().getPath().value(),
                headers, params, null);
    }

    private Map<String, Object> rawMap(MultiValueMap<String, String> form) {
        Map<String, Object> raw = new LinkedHashMap<>();
        form.forEach((k, vs) -> raw.put(k, vs.isEmpty() ? "" : vs.get(0)));
        return raw;
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("could not encode SMS webhook payload", e);
        }
    }
}
