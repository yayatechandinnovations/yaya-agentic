package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and verifies the <b>device pass</b> ({@code chat-channel-design.md} §20):
 * a platform-signed JWT minted after a successful Twilio Verify, asserting that
 * this device holds a verified phone. Signed with a platform HS256 key (a trust
 * root sibling to the per-tenant secrets), so it flows through the existing
 * authenticator chain shape — but here it's minted/checked directly by the chat
 * front door rather than routed through tenant JWT trust.
 *
 * <p>The signing key comes from {@code yaya.agentic.chat.device-pass-secret}. In
 * dev, an absent key falls back to an ephemeral random one (passes do not survive
 * a restart) with a loud warning — production MUST configure it.
 */
@Component
public class DevicePassService {

    private static final Logger log = LoggerFactory.getLogger(DevicePassService.class);
    private static final String ISSUER = "yaya-chat";
    private static final String TYPE = "device-pass";

    private final byte[] key;
    private final Duration ttl;
    private final Duration pinTtl;

    public DevicePassService(
            @Value("${yaya.agentic.chat.device-pass-secret:}") String secret,
            @Value("${yaya.agentic.chat.device-pass-ttl-days:30}") int ttlDays,
            @Value("${yaya.agentic.chat.device-pass-pin-ttl-hours:24}") int pinTtlHours) {
        this.key = deriveKey(secret);
        this.ttl = Duration.ofDays(ttlDays <= 0 ? 30 : ttlDays);
        this.pinTtl = Duration.ofHours(pinTtlHours <= 0 ? 24 : pinTtlHours);
    }

    /** Mint a device pass for a freshly verified device. */
    public String issue(VerifiedDevice device) {
        return issue(device, ttl);
    }

    /**
     * Mint a pass sized to the account's PIN posture ({@code
     * chat-experience-design.md} §5.2): a PIN-holding account gets the short
     * TTL — cheap re-auth is the PIN's whole value — while a PIN-less account
     * keeps the long-lived pass (today's behaviour, undegraded).
     */
    public String issueFor(VerifiedDevice device, boolean pinProtected) {
        return issue(device, pinProtected ? pinTtl : ttl);
    }

    private String issue(VerifiedDevice device, Duration lifetime) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject("chat:" + device.phoneE164())
                    .issuer(ISSUER)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(lifetime)))
                    .claim("typ", TYPE)
                    .claim("phone", device.phoneE164())
                    .claim("name", device.name())
                    .claim("deviceId", device.deviceId());
            // Account id + revocation generation (chat-account-self-service-design.md
            // §5.2). Absent on a legacy pass; the verifier grandfathers those.
            if (device.accountId() != null) builder.claim("acct", device.accountId());
            if (device.passGen() != null) builder.claim("gen", device.passGen());
            JWTClaimsSet claims = builder.build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not mint device pass", e);
        }
    }

    /** Verify a device pass and return the device it asserts, or empty if the
     *  signature is wrong, it's expired, or it isn't a device pass. */
    public Optional<VerifiedDevice> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            SignedJWT jwt = SignedJWT.parse(token.trim());
            if (!jwt.verify(new MACVerifier(key))) return Optional.empty();
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            if (!TYPE.equals(c.getStringClaim("typ"))) return Optional.empty();
            if (!ISSUER.equals(c.getIssuer())) return Optional.empty();
            Date exp = c.getExpirationTime();
            if (exp == null || exp.toInstant().isBefore(Instant.now())) return Optional.empty();
            String phone = c.getStringClaim("phone");
            if (phone == null || !phone.startsWith("+")) return Optional.empty();
            Long gen = c.getClaim("gen") == null ? null : c.getLongClaim("gen");
            return Optional.of(new VerifiedDevice(phone, c.getStringClaim("name"),
                    c.getStringClaim("deviceId"), c.getStringClaim("acct"), gen));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** HS256 needs a ≥256-bit key; derive one by SHA-256 over the configured
     *  secret, or a random key in dev when none is set. */
    private static byte[] deriveKey(String secret) {
        try {
            if (secret == null || secret.isBlank()) {
                log.warn("yaya.agentic.chat.device-pass-secret is not set — using an ephemeral key. "
                        + "Device passes will NOT survive a restart. Configure it in production.");
                byte[] random = new byte[32];
                new SecureRandom().nextBytes(random);
                return random;
            }
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("could not derive device-pass key", e);
        }
    }
}
