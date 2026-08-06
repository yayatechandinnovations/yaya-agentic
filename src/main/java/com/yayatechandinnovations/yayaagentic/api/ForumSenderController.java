package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.ForumDtos;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.channel.system.SenderTrust;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.forum.ForumService;
import com.yayatechandinnovations.yayaagentic.outreach.ReplayGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Optional;

/**
 * Trusted-sender forum posting ({@code chat-experience-design.md} §8.1): the
 * system-messaging trust machinery verbatim — per-request authentication,
 * scope + credential binding, replay guard — pointed at the forum instead of a
 * user's inbox. Announcements land attributed to the sender and can be pinned.
 * Opaque 404s throughout, mirroring {@link SystemInboundController}.
 */
@RestController
@RequestMapping("/v1/integrations/{tenantId}/senders/{senderId}/forum-posts")
public class ForumSenderController {

    private final Authenticator authenticator;
    private final SystemSenderService senders;
    private final ReplayGuard replayGuard;
    private final ChatEntryPointService entryPoints;
    private final ForumService forum;

    public ForumSenderController(Authenticator authenticator,
                                 SystemSenderService senders,
                                 ReplayGuard replayGuard,
                                 ChatEntryPointService entryPoints,
                                 ForumService forum) {
        this.authenticator = authenticator;
        this.senders = senders;
        this.replayGuard = replayGuard;
        this.entryPoints = entryPoints;
        this.forum = forum;
    }

    @PostMapping
    public Mono<ForumDtos.SenderPostResponse> post(@PathVariable("tenantId") String tenantId,
                                                   @PathVariable("senderId") String senderId,
                                                   @RequestBody ForumDtos.SenderPostRequest body,
                                                   ServerWebExchange exchange) {
        Ids.TenantId tenant = new Ids.TenantId(tenantId);
        Ids.SystemSenderId sid = new Ids.SystemSenderId(senderId);
        return Mono.fromSupplier(() -> {
            SystemSender sender = senders.find(tenant, sid)
                    .filter(SystemSender::enabled)
                    .orElseThrow(ForumSenderController::opaque404);

            Principal principal = authenticate(tenant, exchange);
            requireTrust(sender, principal);
            requireFreshRequest(tenant, sid, exchange);

            if (body == null || body.handle() == null || body.handle().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "handle required");
            }
            ChatEntryPoint ep = entryPoints.findByHandle(body.handle().trim())
                    .filter(e -> e.tenant().equals(tenant))
                    .orElseThrow(ForumSenderController::opaque404);
            if (!forum.isEnabled(ep.id().value())) throw opaque404();

            String display = body.displayName() == null || body.displayName().isBlank()
                    ? sender.displayName() : body.displayName().trim();
            try {
                var created = forum.postAsSender(tenant.value(), ep.id().value(),
                        sid.value(), display, body.body(),
                        Boolean.TRUE.equals(body.pinned()));
                return new ForumDtos.SenderPostResponse(created.getId());
            } catch (ForumService.ForumException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* --------- trust (mirrors SystemInboundController §4.1 verbatim) -------- */

    private Principal authenticate(Ids.TenantId tenant, ServerWebExchange exchange) {
        Map<String, String> headers = exchange.getRequest().getHeaders().toSingleValueMap();
        return authenticator.tryAuthenticate(new AuthContext(tenant, headers, Optional.empty()))
                .filter(p -> !isAnonymous(p))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "sender authentication failed"));
    }

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
        String scope = tenant.value() + ":" + sid.value() + ":forum";
        ReplayGuard.Result r = replayGuard.check(scope, nonce, epochMs);
        if (r instanceof ReplayGuard.Result.Rejected rej) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "replay rejected: " + rej.reason());
        }
    }

    private static boolean isAnonymous(Principal p) {
        String s = p.subject();
        return s == null || s.startsWith("anonymous-") || s.contains(":anon:");
    }

    private static String header(ServerWebExchange exchange, String name) {
        String v = exchange.getRequest().getHeaders().getFirst(name);
        if (v == null || v.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " required");
        }
        return v;
    }

    private static ResponseStatusException opaque404() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
