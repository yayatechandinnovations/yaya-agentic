package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.BindingPolicyResolver;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.DefaultPrincipalPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.PrincipalPolicyMaterializer;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
 * <p>The actual scope/claim composition lives in {@link
 * PrincipalPolicyMaterializer}; the {@link
 * com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding}'s
 * {@link DefaultPrincipalPolicy} drives the materialization. The
 * {@link com.yayatechandinnovations.yayaagentic.auth.Authorizer} chain stays
 * the enforcement point — never the tool, and never this authenticator.
 *
 * <p>See {@code whatsapp-channel-design.md} §9.3 and
 * {@code whatsapp-channel-auth-design.md} §5.
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

    private final BindingPolicyResolver bindingPolicyResolver;
    private final PrincipalPolicyMaterializer materializer;

    public WhatsAppPhoneAuthenticator(BindingPolicyResolver bindingPolicyResolver,
                                      PrincipalPolicyMaterializer materializer) {
        this.bindingPolicyResolver = bindingPolicyResolver;
        this.materializer = materializer;
    }

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

        DefaultPrincipalPolicy policy =
                bindingPolicyResolver.policyFor(ctx.tenant(), new SenderId(sender));
        return Optional.of(materializer.materialize(policy, from, sender, ctx));
    }

    private static String headerCi(AuthContext ctx, String name) {
        for (var entry : ctx.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }
}
