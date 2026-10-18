package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.ConversationHoldService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ConversationHoldEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Operator takeover for a trusted integration ({@code whatsapp-channel-design.md} §11.3). The support desk that
 * answers "talk to a person" itself holds the conversation while a person is on it, reads back what the customer
 * wrote meanwhile, and releases it when done. Same trust gate as the ingest edge ({@link IntegrationSenderGate}),
 * so one service token covers sending, holding and reading.
 *
 * <p>The hold is keyed by the end user's phone, which the desk knows; sender ids stay ours.</p>
 */
@RestController
@RequestMapping("/v1/integrations/{tenantId}/senders/{senderId}/holds")
public class SystemConversationHoldController {

    /** {@code minutes} is clamped to {@link ConversationHoldService#MAX_HOLD}; absent means 24 hours. */
    public record HoldRequest(String phone, Integer minutes, String reason) {
    }

    public record HoldResponse(String phone, Instant heldUntil, String reason) {
    }

    public record HeldMessage(Instant at, String text) {
    }

    public record HeldMessages(String phone, boolean held, Instant heldUntil, List<HeldMessage> messages) {
    }

    private final IntegrationSenderGate gate;
    private final ConversationHoldService holds;
    private final InboundMessageRepository inbound;

    public SystemConversationHoldController(IntegrationSenderGate gate, ConversationHoldService holds,
                                            InboundMessageRepository inbound) {
        this.gate = gate;
        this.holds = holds;
        this.inbound = inbound;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<HoldResponse> hold(@PathVariable("tenantId") String tenantId,
                                   @PathVariable("senderId") String senderId,
                                   @RequestBody HoldRequest body,
                                   ServerWebExchange exchange) {
        Ids.TenantId tenant = new Ids.TenantId(tenantId);
        return Mono.fromSupplier(() -> {
            gate.admit(tenant, new Ids.SystemSenderId(senderId), exchange);
            String phone = requirePhone(body == null ? null : body.phone());
            Duration d = body == null || body.minutes() == null ? null : Duration.ofMinutes(body.minutes());
            ConversationHoldEntity held = holds.hold(tenant, phone, d, body == null ? null : body.reason());
            return new HoldResponse(phone, held.getHeldUntil().toInstant(), held.getReason());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/release")
    public Mono<HoldResponse> release(@PathVariable("tenantId") String tenantId,
                                      @PathVariable("senderId") String senderId,
                                      @RequestBody HoldRequest body,
                                      ServerWebExchange exchange) {
        Ids.TenantId tenant = new Ids.TenantId(tenantId);
        return Mono.fromSupplier(() -> {
            gate.admit(tenant, new Ids.SystemSenderId(senderId), exchange);
            String phone = requirePhone(body == null ? null : body.phone());
            holds.release(tenant, phone);
            return new HoldResponse(phone, null, null);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** What the customer wrote since {@code since} (default: when the hold started). */
    @GetMapping("/messages")
    public Mono<HeldMessages> messages(@PathVariable("tenantId") String tenantId,
                                       @PathVariable("senderId") String senderId,
                                       @RequestParam("phone") String phone,
                                       @RequestParam(name = "since", required = false) String since,
                                       ServerWebExchange exchange) {
        Ids.TenantId tenant = new Ids.TenantId(tenantId);
        return Mono.fromSupplier(() -> {
            gate.admit(tenant, new Ids.SystemSenderId(senderId), exchange);
            String number = requirePhone(phone);
            ConversationHoldEntity hold = holds.find(tenant, number).orElse(null);
            OffsetDateTime from = parseSince(since)
                    .orElseGet(() -> hold == null
                            ? OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)
                            : hold.getHeldFrom());
            List<HeldMessage> out = inbound
                    .findTop50ByTenantIdAndEndUserPhoneAndReceivedAtAfterOrderByReceivedAtAsc(tenantId, number, from)
                    .stream()
                    .filter(m -> m.getBodyRedacted() != null && !m.getBodyRedacted().isBlank())
                    .map(SystemConversationHoldController::toMessage)
                    .toList();
            return new HeldMessages(number, hold != null, hold == null ? null : hold.getHeldUntil().toInstant(), out);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static HeldMessage toMessage(InboundMessageEntity m) {
        return new HeldMessage(m.getReceivedAt().toInstant(), m.getBodyRedacted());
    }

    private static java.util.Optional<OffsetDateTime> parseSince(String since) {
        if (since == null || since.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Instant.parse(since.trim()).atOffset(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "since must be an ISO-8601 instant");
        }
    }

    private static String requirePhone(String phone) {
        String p = phone == null ? "" : phone.trim();
        if (p.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phone required");
        }
        return p;
    }
}
