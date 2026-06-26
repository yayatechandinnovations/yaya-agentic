package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Mints the end-user {@link Principal} for an inbound WhatsApp message. The
 * channel webhook controller / dispatch worker stamps the canonical fields
 * into {@link AuthContext#headers()} under reserved keys; this authenticator
 * is the only one that reads them.
 *
 * <p>{@code @Order(900)} — sits AFTER {@link
 * com.yayatechandinnovations.yayaagentic.auth.oidc.OidcAuthenticator}
 * ({@code @Order(100)}) and {@link
 * com.yayatechandinnovations.yayaagentic.auth.jwt.SharedSecretJwtAuthenticator}
 * ({@code @Order(150)}) — JWT-backed callers stay JWT-backed; WhatsApp
 * inbound never wins authentication against an explicit Bearer.
 *
 * <p>Scopes are deliberately narrow ({@code channel:whatsapp} + {@code
 * channel:read}). Any sensitive tool a profile exposes requires a wider
 * scope, which a phone-only principal can't unlock without an explicit
 * identity-binding step (§9.4). The
 * {@link com.yayatechandinnovations.yayaagentic.auth.Authorizer} chain is
 * the enforcement point — never the tool.
 *
 * <p>See {@code whatsapp-channel-design.md} §9.3.
 */
@Component
@Order(900)
public class WhatsAppPhoneAuthenticator implements Authenticator {

    /** Channel adapter / webhook controller stamps these into the AuthContext
     *  headers map. Both keys are checked case-insensitively. */
    public static final String HEADER_END_USER     = "x-channel-end-user";
    public static final String HEADER_SENDER_ID    = "x-channel-sender-id";
    public static final String HEADER_LOCALE       = "x-channel-locale";
    public static final String HEADER_TWILIO_FROM  = "x-channel-twilio-contact-sid";

    @Override
    public String name() {
        return "channel-whatsapp-phone";
    }

    @Override
    public Optional<Principal> tryAuthenticate(AuthContext ctx) throws AuthenticationException {
        String from   = headerCi(ctx, HEADER_END_USER);
        String sender = headerCi(ctx, HEADER_SENDER_ID);
        if (from == null || sender == null) {
            return Optional.empty();
        }
        if (!from.startsWith("+")) {
            throw new AuthenticationException(name() + ": malformed end-user phone");
        }
        String locale = headerCi(ctx, HEADER_LOCALE);
        String twilioContactSid = headerCi(ctx, HEADER_TWILIO_FROM);

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("phone", from);
        claims.put("channel.sender", sender);
        claims.put("channel.kind", "whatsapp");
        if (locale != null && !locale.isBlank()) claims.put("locale", locale);
        if (twilioContactSid != null && !twilioContactSid.isBlank()) {
            claims.put("twilioContactSid", twilioContactSid);
        }
        return Optional.of(new Principal(
                "whatsapp:" + from,
                ctx.tenant(),
                Set.of("channel:whatsapp", "channel:read"),
                claims,
                Instant.now()));
    }

    private static String headerCi(AuthContext ctx, String name) {
        for (var entry : ctx.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }
}
