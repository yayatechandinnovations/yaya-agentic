package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.SystemInboundDtos.IngestRequest;
import com.yayatechandinnovations.yayaagentic.api.dto.SystemInboundDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.SystemInboundDtos.IngestResponse;
import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemInboundMessage;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.outreach.OutreachException;
import com.yayatechandinnovations.yayaagentic.outreach.OutreachService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * The ingest edge for trusted integration senders ({@code
 * system-messaging-design.md} §5). Public route (not behind the operator-auth
 * filter): trust is proven per-request by {@link IntegrationSenderGate} —
 * the sender's own authenticator, scope, replay guard and credential
 * binding (§4.1) — before the message enters {@link OutreachService}.
 */
@RestController
@RequestMapping("/v1/integrations/{tenantId}/senders/{senderId}/messages")
public class SystemInboundController {

    private final IntegrationSenderGate gate;
    private final OutreachService outreach;

    public SystemInboundController(IntegrationSenderGate gate, OutreachService outreach) {
        this.gate = gate;
        this.outreach = outreach;
    }

    @PostMapping
    public Mono<IngestResponse> ingest(@PathVariable("tenantId") String tenantId,
                                       @PathVariable("senderId") String senderId,
                                       @RequestBody IngestRequest body,
                                       ServerWebExchange exchange) {
        Ids.TenantId tenant = new Ids.TenantId(tenantId);
        Ids.SystemSenderId sid = new Ids.SystemSenderId(senderId);
        return Mono.fromSupplier(() -> {
            IntegrationSenderGate.Admitted admitted = gate.admit(tenant, sid, exchange);

            try {
                SystemInboundMessage msg = toMessage(tenant, sid, body, exchange);
                return IngestResponse.of(outreach.ingest(msg, new OutreachService.OutreachAuth(admitted.principal())));
            } catch (OutreachException e) {
                throw map(e);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Delivery status of a message this sender posted (§14.2 of
     * whatsapp-ops-integration-design.md): same gate as ingest, and an id from
     * another sender reads as unknown.
     */
    @GetMapping("/{outreachId}")
    public Mono<SystemInboundDtos.StatusResponse> status(@PathVariable("tenantId") String tenantId,
                                                         @PathVariable("senderId") String senderId,
                                                         @PathVariable("outreachId") String outreachId,
                                                         ServerWebExchange exchange) {
        Ids.TenantId tenant = new Ids.TenantId(tenantId);
        Ids.SystemSenderId sid = new Ids.SystemSenderId(senderId);
        return Mono.fromSupplier(() -> {
            gate.admit(tenant, sid, exchange);
            var status = outreach.statusFor(tenant, sid, new Ids.OutreachId(outreachId))
                    .orElseThrow(IntegrationSenderGate::opaque404);
            return new SystemInboundDtos.StatusResponse(status.id().value(), status.status(),
                    status.receivedAt(), status.deliveries().stream()
                    .map(d -> new SystemInboundDtos.DeliveryView(d.channel(), d.fallbackReason(),
                            d.providerMessageId(), d.status(), d.statusAt()))
                    .toList());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ---------------------------- mapping -------------------------------- */

    private SystemInboundMessage toMessage(Ids.TenantId tenant, Ids.SystemSenderId sid,
                                           IngestRequest body, ServerWebExchange exchange) {
        if (body == null || body.recipient() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recipient required");
        }
        OutreachRecipient recipient = body.recipient().toDomain();
        String dedupKey = body.dedupKey() != null && !body.dedupKey().isBlank()
                ? body.dedupKey().trim()
                : header(exchange, "X-Idem-Key");
        return new SystemInboundMessage(
                tenant, sid, recipient, body.body(), body.mediaDomain(),
                body.correlation() == null ? null : body.correlation().toDomain(),
                dedupKey,
                body.metadata() == null ? Map.of() : body.metadata(),
                body.actionsDomain(),
                deliveryOf(body));
    }

    private static com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest deliveryOf(IngestRequest body) {
        try {
            return body.deliveryDomain();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown delivery.channel");
        }
    }

    private static ResponseStatusException map(OutreachException e) {
        HttpStatus status = switch (e.kind()) {
            case ADDRESSING_DENIED -> HttpStatus.FORBIDDEN;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            // Disabled/unknown sender and unresolvable recipient stay opaque.
            case SENDER_DISABLED, UNRESOLVABLE_RECIPIENT -> HttpStatus.NOT_FOUND;
        };
        return new ResponseStatusException(status, e.getMessage());
    }

    private static String header(ServerWebExchange exchange, String name) {
        return IntegrationSenderGate.header(exchange, name);
    }
}
