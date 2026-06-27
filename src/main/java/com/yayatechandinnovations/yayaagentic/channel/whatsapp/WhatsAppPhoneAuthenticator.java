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
 * Mints the baseline end-user {@link Principal} for an inbound WhatsApp message.
 * The channel adapter / webhook controller stamps the canonical fields into
 * {@link AuthContext#headers()} under reserved keys; this authenticator is the
 * only reader.
 *
 * <p>{@code @Order(900)} — sits AFTER {@link
 * com.yayatechandinnovations.yayaagentic.auth.oidc.OidcAuthenticator}
 * ({@code @Order(100)}) and {@link
 * com.yayatechandinnovations.yayaagentic.auth.jwt.SharedSecretJwtAuthenticator}
 * ({@code @Order(150)}). A JWT-backed inbound (Authorization header) stays
 * JWT-backed; this authenticator is only consulted when no upstream issued a
 * Principal — typically real Twilio inbound. When an {@code AuthResolver} runs
 * for the binding it returns a JWT, which then flows through the JWT/OIDC
 * authenticator on the next turn, not through this one.
 *
 * <p>The minted Principal carries the {@code whatsapp:+E164} subject, the two
 * baseline channel scopes, and the reserved channel claims ({@code phone},
 * {@code channel.sender}, {@code channel.kind}, optional {@code locale} and
 * {@code twilioContactSid}). Identity enrichment (anything beyond verified
 * phone) is the resolver's job downstream — see
 * {@code docs/design/channel-auth-resolver-design.md} §5.
 */
@Component
@Order(900)
public class WhatsAppPhoneAuthenticator implements Authenticator {

    public static final String HEADER_END_USER     = "x-channel-end-user";
    public static final String HEADER_SENDER_ID    = "x-channel-sender-id";
    public static final String HEADER_LOCALE       = "x-channel-locale";
    public static final String HEADER_TWILIO_FROM  = "x-channel-twilio-contact-sid";

    static final Set<String> BASELINE_SCOPES =
            Set.of("channel:whatsapp", "channel:read");

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

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("phone", from);
        claims.put("channel.sender", sender);
        claims.put("channel.kind", "whatsapp");

        String locale = headerCi(ctx, HEADER_LOCALE);
        if (locale != null && !locale.isBlank()) claims.put("locale", locale);

        String twilioContactSid = headerCi(ctx, HEADER_TWILIO_FROM);
        if (twilioContactSid != null && !twilioContactSid.isBlank()) {
            claims.put("twilioContactSid", twilioContactSid);
        }

        return Optional.of(new Principal(
                "whatsapp:" + from,
                ctx.tenant(),
                BASELINE_SCOPES,
                Map.copyOf(claims),
                Instant.now()));
    }

    private static String headerCi(AuthContext ctx, String name) {
        for (var entry : ctx.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }
}
