package com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Materializes the end-user {@link Principal} from a verified WhatsApp
 * inbound. Centralizes the rule that channel-reserved claims and the two
 * baseline scopes ({@code channel:whatsapp}, {@code channel:read}) are
 * non-negotiable — a misbehaving {@link DefaultPrincipalPolicy} cannot drop
 * them or replace them with attacker-controlled values.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §5.3.
 */
@Component
public class PrincipalPolicyMaterializer {

    /** Scopes EVERY WhatsApp-minted Principal carries, regardless of policy. */
    static final Set<String> BASELINE_SCOPES = Set.of("channel:whatsapp", "channel:read");

    /**
     * Claim keys reserved for the channel. A {@code PhoneAsPrincipal} policy
     * supplying any of these in {@code additionalClaims} is rejected at
     * materialization time — they would silently lose to the channel stamp,
     * which is a confusing tenant-side bug we'd rather raise loudly.
     */
    public static final Set<String> RESERVED_CLAIM_KEYS = Set.of(
            "phone",
            "channel.sender",
            "channel.kind",
            "locale",
            "twilioContactSid");

    /**
     * @param policy the binding's {@link DefaultPrincipalPolicy}
     * @param phone  E.164 phone number from the verified Twilio inbound
     * @param sender the platform-stable WhatsApp sender id
     * @param ctx    the {@link AuthContext} the authenticator received; used to
     *               pick up the optional locale and Twilio contact SID headers
     */
    public Principal materialize(DefaultPrincipalPolicy policy,
                                 String phone,
                                 String sender,
                                 AuthContext ctx) {
        return switch (policy) {
            case DefaultPrincipalPolicy.Anonymous a       -> anonymous(phone, sender, ctx);
            case DefaultPrincipalPolicy.PhoneAsPrincipal p -> phoneAsPrincipal(phone, sender, ctx, p);
            case DefaultPrincipalPolicy.LookupPrincipal l  -> throw new UnsupportedOperationException(
                    "LookupPrincipal materialization arrives in W-A2 — resolver bean '"
                            + l.resolverId() + "' is not yet wired");
        };
    }

    private Principal anonymous(String phone, String sender, AuthContext ctx) {
        return new Principal(
                "whatsapp:" + phone,
                ctx.tenant(),
                Set.copyOf(BASELINE_SCOPES),
                channelClaims(phone, sender, ctx),
                Instant.now());
    }

    private Principal phoneAsPrincipal(String phone,
                                       String sender,
                                       AuthContext ctx,
                                       DefaultPrincipalPolicy.PhoneAsPrincipal policy) {
        rejectReservedClaimCollisions(policy.additionalClaims());

        Set<String> scopes = new HashSet<>(BASELINE_SCOPES);
        scopes.addAll(policy.additionalScopes());

        Map<String, Object> claims = new LinkedHashMap<>(policy.additionalClaims());
        claims.putAll(channelClaims(phone, sender, ctx));   // channel stamp wins

        return new Principal(
                "whatsapp:" + phone,
                ctx.tenant(),
                Set.copyOf(scopes),
                Map.copyOf(claims),
                Instant.now());
    }

    private static Map<String, Object> channelClaims(String phone, String sender, AuthContext ctx) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("phone", phone);
        claims.put("channel.sender", sender);
        claims.put("channel.kind", "whatsapp");

        String locale = headerCi(ctx, "x-channel-locale");
        if (locale != null && !locale.isBlank()) claims.put("locale", locale);

        String twilioContactSid = headerCi(ctx, "x-channel-twilio-contact-sid");
        if (twilioContactSid != null && !twilioContactSid.isBlank()) {
            claims.put("twilioContactSid", twilioContactSid);
        }
        return claims;
    }

    private static void rejectReservedClaimCollisions(Map<String, Object> additionalClaims) {
        if (additionalClaims == null || additionalClaims.isEmpty()) return;
        Set<String> collisions = new HashSet<>();
        for (String key : additionalClaims.keySet()) {
            if (RESERVED_CLAIM_KEYS.contains(key)) collisions.add(key);
        }
        if (!collisions.isEmpty()) {
            throw new IllegalArgumentException(
                    "PhoneAsPrincipal.additionalClaims contains reserved channel keys: " + collisions
                            + " (reserved: " + RESERVED_CLAIM_KEYS + ")");
        }
    }

    private static String headerCi(AuthContext ctx, String name) {
        for (var entry : ctx.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }
}
