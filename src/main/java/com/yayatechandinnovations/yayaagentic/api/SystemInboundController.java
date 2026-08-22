package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.SystemInboundDtos.IngestRequest;
import com.yayatechandinnovations.yayaagentic.api.dto.SystemInboundDtos.IngestResponse;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.channel.system.SenderTrust;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemInboundMessage;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.outreach.OutreachException;
import com.yayatechandinnovations.yayaagentic.outreach.OutreachService;
import com.yayatechandinnovations.yayaagentic.outreach.ReplayGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Optional;

/**
 * The ingest edge for trusted integration senders ({@code
 * system-messaging-design.md} §5). Public route (not behind the operator-auth
 * filter): trust is proven per-request by the sender's configured {@link
 * Authenticator}, then defended in depth — scope, replay guard, credential
 * binding (§4.1) — before the message enters {@link OutreachService}.
 *
 * <p>Deliberately opaque on failure: a bad sender, a wrong tenant, or a
 * disabled sender all collapse to a 404 so the edge never confirms which
 * senders exist.
 */
@RestController
@RequestMapping("/v1/integrations/{tenantId}/senders/{senderId}/messages")
public class SystemInboundController {

    private final Authenticator authenticator;
    private final SystemSenderService senders;
    private final ReplayGuard replayGuard;
    private final OutreachService outreach;

    public SystemInboundController(Authenticator authenticator,
                                   SystemSenderService senders,
                                   ReplayGuard replayGuard,
                                   OutreachService outreach) {
        this.authenticator = authenticator;
        this.senders = senders;
        this.replayGuard = replayGuard;
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
            SystemSender sender = senders.find(tenant, sid)
                    .filter(SystemSender::enabled)
                    .orElseThrow(SystemInboundController::opaque404);

            Principal principal = authenticate(tenant, exchange);
            requireTrust(sender, principal);
            requireFreshRequest(tenant, sid, exchange);

            SystemInboundMessage msg = toMessage(tenant, sid, body, exchange);
            try {
                return IngestResponse.of(outreach.ingest(msg, new OutreachService.OutreachAuth(principal)));
            } catch (OutreachException e) {
                throw map(e);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ------------------------------ trust -------------------------------- */

    private Principal authenticate(Ids.TenantId tenant, ServerWebExchange exchange) {
        Map<String, String> headers = exchange.getRequest().getHeaders().toSingleValueMap();
        return authenticator.tryAuthenticate(new AuthContext(tenant, headers, Optional.empty()))
                .filter(p -> !isAnonymous(p))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "sender authentication failed"));
    }

    /** §4.1: the verified principal must carry {@code system:inbound:send} plus
     *  any scopes the sender requires, and — when the sender pins a credential —
     *  it must be the one that signed the token. */
    private void requireTrust(SystemSender sender, Principal principal) {
        SenderTrust trust = sender.trust();
        if (!principal.scopes().contains(SenderTrust.SEND_SCOPE)
                || !principal.scopes().containsAll(trust.requiredScopes())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "insufficient scope");
        }
        if (!trust.credentialRef().isBlank()) {
            Object iss = principal.claims() == null ? null : principal.claims().get("iss");
            if (iss == null || !trust.credentialRef().equals(String.valueOf(iss))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "untrusted credential");
            }
        }
    }

    /** §4.1 #2: mandatory nonce + timestamp; reject stale or replayed requests. */
    private void requireFreshRequest(Ids.TenantId tenant, Ids.SystemSenderId sid,
                                     ServerWebExchange exchange) {
        String nonce = header(exchange, "X-Nonce");
        String ts = header(exchange, "X-Timestamp");
        long epochMs;
        try {
            epochMs = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "X-Timestamp (epoch millis) required");
        }
        String scope = tenant.value() + ":" + sid.value();
        ReplayGuard.Result r = replayGuard.check(scope, nonce, epochMs);
        if (r instanceof ReplayGuard.Result.Rejected rej) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "replay rejected: " + rej.reason());
        }
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
                body.actionsDomain());
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

    private static ResponseStatusException opaque404() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
    }

    private static boolean isAnonymous(Principal p) {
        String s = p.subject();
        return s == null || s.startsWith("anonymous-") || s.contains(":anon:");
    }

    private static String header(ServerWebExchange exchange, String name) {
        String v = exchange.getRequest().getHeaders().getFirst(name);
        return v == null ? "" : v.trim();
    }
}
