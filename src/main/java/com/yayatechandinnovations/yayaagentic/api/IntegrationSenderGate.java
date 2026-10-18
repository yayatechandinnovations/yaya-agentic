package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.channel.system.SenderTrust;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.outreach.ReplayGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;
import java.util.Optional;

/**
 * The trust gate every {@code /v1/integrations/{tenant}/senders/{sender}/**} route passes through ({@code
 * system-messaging-design.md} §4.1, §5): the sender exists and is enabled, the request carries a verified
 * non-anonymous principal with {@code system:inbound:send} plus the sender's required scopes, the credential is the
 * one the sender pins, and the nonce and timestamp are fresh.
 *
 * <p>Deliberately opaque: an unknown sender, a wrong tenant and a disabled sender all collapse to 404, so the edge
 * never confirms which senders exist.</p>
 */
@Component
public class IntegrationSenderGate {

    /** A request that cleared the gate. */
    public record Admitted(SystemSender sender, Principal principal) {
    }

    private final Authenticator authenticator;
    private final SystemSenderService senders;
    private final ReplayGuard replayGuard;

    public IntegrationSenderGate(Authenticator authenticator, SystemSenderService senders, ReplayGuard replayGuard) {
        this.authenticator = authenticator;
        this.senders = senders;
        this.replayGuard = replayGuard;
    }

    public Admitted admit(Ids.TenantId tenant, Ids.SystemSenderId sid, ServerWebExchange exchange) {
        SystemSender sender = senders.find(tenant, sid)
                .filter(SystemSender::enabled)
                .orElseThrow(IntegrationSenderGate::opaque404);
        Principal principal = authenticate(tenant, exchange);
        requireTrust(sender, principal);
        requireFreshRequest(tenant, sid, exchange);
        return new Admitted(sender, principal);
    }

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
    private void requireFreshRequest(Ids.TenantId tenant, Ids.SystemSenderId sid, ServerWebExchange exchange) {
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

    public static ResponseStatusException opaque404() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
    }

    private static boolean isAnonymous(Principal p) {
        String s = p.subject();
        return s == null || s.startsWith("anonymous-") || s.contains(":anon:");
    }

    public static String header(ServerWebExchange exchange, String name) {
        String v = exchange.getRequest().getHeaders().getFirst(name);
        return v == null ? "" : v.trim();
    }
}
