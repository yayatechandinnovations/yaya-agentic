package com.yayatechandinnovations.yayaagentic.auth.anon;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Mints the minimal-scope principal for an anonymous chat session
 * ({@code yaya-agentic-design.md} §5.4 — the {@code AnonymousAuthenticator}
 * reference impl; {@code chat-channel-design.md} §8.1).
 *
 * <p>"Anonymous" is a real, minted {@link Principal} — not "no auth". It widens
 * <em>who can start</em> a session, never <em>what they can do</em>: every tool
 * call and knowledge read still passes the {@code Authorizer} chain, and an
 * anonymous principal simply fails authz for anything requiring a verified scope.
 *
 * <p>The {@code deviceId} stabilises the subject across messages for session
 * continuity and rate-limiting; it is <em>not</em> trusted for authorization
 * (a hostile client can rotate it — per-IP limits + an optional challenge
 * backstop it, §7.1).
 */
@Component
public class AnonymousAuthenticator {

    public static final Set<String> BASELINE_SCOPES = Set.of("channel:chat", "channel:read");

    public Principal mintAnonymous(Ids.TenantId tenant, String handle, String deviceId) {
        String device = (deviceId == null || deviceId.isBlank()) ? "nodev" : deviceId.trim();
        return new Principal(
                "chat:" + handle + ":anon:" + device,
                tenant,
                BASELINE_SCOPES,
                Map.of("channel", "chat", "entryPoint", handle, "anon", true),
                Instant.now());
    }
}
