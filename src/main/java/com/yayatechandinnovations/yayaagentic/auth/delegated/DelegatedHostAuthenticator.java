package com.yayatechandinnovations.yayaagentic.auth.delegated;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Trust-the-host authentication. The host application produces an Ed25519
 * signature over a small JSON identity blob and sends it via two headers:
 *
 * <ul>
 *   <li>{@code X-Yaya-Identity}: base64url of a JSON document
 *       {@code {tenant, subject, scopes[], exp, ...claims}}</li>
 *   <li>{@code X-Yaya-Identity-Sig}: base64url Ed25519 signature over the
 *       raw identity bytes (i.e. over the decoded JSON)</li>
 * </ul>
 *
 * Disabled when {@code yaya.agentic.auth.delegated-host.public-key-pem} is
 * empty. The SPI shape is real so the first host integrating in M4 can plug
 * in without engine changes.
 */
@Component
@Order(300)
public class DelegatedHostAuthenticator implements Authenticator {

    public static final String IDENTITY_HEADER = "X-Yaya-Identity";
    public static final String SIGNATURE_HEADER = "X-Yaya-Identity-Sig";

    private final PublicKey verifyingKey;
    private final ObjectMapper json;

    public DelegatedHostAuthenticator(YayaAgenticProperties props, ObjectMapper json) {
        this.verifyingKey = parseKey(props);
        this.json = json;
    }

    @Override
    public String name() { return "delegated-host"; }

    @Override
    public Optional<Principal> tryAuthenticate(AuthContext ctx) throws AuthenticationException {
        if (verifyingKey == null) return Optional.empty();
        if (ctx.headers() == null) return Optional.empty();
        String identityHeader = headerCaseInsensitive(ctx, IDENTITY_HEADER);
        String signatureHeader = headerCaseInsensitive(ctx, SIGNATURE_HEADER);
        if (identityHeader == null || signatureHeader == null) return Optional.empty();

        byte[] identityBytes;
        byte[] signatureBytes;
        try {
            identityBytes = Base64.getUrlDecoder().decode(identityHeader);
            signatureBytes = Base64.getUrlDecoder().decode(signatureHeader);
        } catch (IllegalArgumentException e) {
            throw new AuthenticationException("delegated-host: malformed base64");
        }

        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(verifyingKey);
            sig.update(identityBytes);
            if (!sig.verify(signatureBytes)) {
                throw new AuthenticationException("delegated-host: signature mismatch");
            }
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationException("delegated-host: verifier failure", e);
        }

        Map<String, Object> identity;
        try {
            identity = json.readValue(new String(identityBytes, StandardCharsets.UTF_8),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new AuthenticationException("delegated-host: bad JSON", e);
        }
        Object expObj = identity.get("exp");
        if (expObj instanceof Number expNum
                && Instant.now().getEpochSecond() > expNum.longValue()) {
            throw new AuthenticationException("delegated-host: identity expired");
        }

        String subject = String.valueOf(identity.getOrDefault("subject", "unknown"));
        String tenant = String.valueOf(identity.getOrDefault("tenant",
                ctx.tenant() != null ? ctx.tenant().value() : "default"));
        Object scopesObj = identity.get("scopes");
        Set<String> scopes = scopesObj instanceof List<?> list
                ? list.stream().filter(Objects::nonNull).map(Object::toString).collect(Collectors.toUnmodifiableSet())
                : Set.of();

        return Optional.of(new Principal(
                subject,
                new Ids.TenantId(tenant),
                scopes,
                Map.copyOf(identity),
                Instant.now()));
    }

    private static PublicKey parseKey(YayaAgenticProperties props) {
        if (props.auth() == null || props.auth().delegatedHost() == null) return null;
        String pem = props.auth().delegatedHost().publicKeyPem();
        if (pem == null || pem.isBlank()) return null;
        try {
            String stripped = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                    .replaceAll("-----END [A-Z ]+-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(stripped);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("delegated-host: unable to parse public key", e);
        }
    }

    private static String headerCaseInsensitive(AuthContext ctx, String name) {
        String v = ctx.headers().get(name);
        if (v != null) return v;
        return ctx.headers().get(name.toLowerCase(Locale.ROOT));
    }
}
