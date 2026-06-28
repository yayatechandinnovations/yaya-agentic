package com.yayatechandinnovations.yayaagentic.channel.sms;

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
 * Mints the baseline end-user {@link Principal} for an inbound SMS, the SMS twin
 * of {@code channel.whatsapp.WhatsAppPhoneAuthenticator}. Subject is
 * {@code sms:+E164}; baseline scopes are {@code channel:sms} + {@code channel:read}.
 *
 * <p>Deliberately reads SMS-specific reserved headers ({@code x-channel-sms-*})
 * rather than the WhatsApp authenticator's {@code x-channel-*} headers, so the
 * two never fire for each other's traffic — zero changes to the shipped
 * WhatsApp path. A shared {@code x-channel-kind} discriminator is the eventual
 * unification seam, alongside the dispatcher generalization the design calls for.
 *
 * <p>{@code @Order(910)} — after the JWT/OIDC authenticators (100/150) and the
 * WhatsApp phone authenticator (900); a JWT-backed inbound stays JWT-backed and
 * this is only consulted for real BSP inbound that carries no upstream Principal.
 */
@Component
@Order(910)
public class SmsPhoneAuthenticator implements Authenticator {

    public static final String HEADER_END_USER   = "x-channel-sms-end-user";
    public static final String HEADER_SENDER_ID   = "x-channel-sms-sender-id";
    public static final String HEADER_LOCALE      = "x-channel-sms-locale";
    public static final String HEADER_MESSAGE_SID = "x-channel-sms-message-sid";

    public static final Set<String> BASELINE_SCOPES = Set.of("channel:sms", "channel:read");

    public static final Set<String> RESERVED_CLAIM_KEYS =
            Set.of("phone", "channel.sender", "channel.kind", "locale", "twilioMessageSid");

    /** Channel-reserved claims derived from {@link AuthContext} headers. */
    public static Map<String, Object> channelClaims(AuthContext ctx) {
        Map<String, Object> claims = new LinkedHashMap<>();
        String phone = headerCi(ctx, HEADER_END_USER);
        if (phone != null) claims.put("phone", phone);
        String sender = headerCi(ctx, HEADER_SENDER_ID);
        if (sender != null) claims.put("channel.sender", sender);
        claims.put("channel.kind", "sms");
        String locale = headerCi(ctx, HEADER_LOCALE);
        if (locale != null && !locale.isBlank()) claims.put("locale", locale);
        String messageSid = headerCi(ctx, HEADER_MESSAGE_SID);
        if (messageSid != null && !messageSid.isBlank()) claims.put("twilioMessageSid", messageSid);
        return claims;
    }

    @Override
    public String name() {
        return "channel-sms-phone";
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
        return Optional.of(new Principal(
                "sms:" + from,
                ctx.tenant(),
                BASELINE_SCOPES,
                Map.copyOf(channelClaims(ctx)),
                Instant.now()));
    }

    private static String headerCi(AuthContext ctx, String name) {
        for (var entry : ctx.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }
}
