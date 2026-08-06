package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountDeviceEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountDeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The device registry behind PIN unlock and per-device sign-out
 * ({@code chat-experience-design.md} §5). Each activated device holds a 32-byte
 * secret (returned once, stored hashed) that proves "this is an enrolled device"
 * on the unlock endpoint — which is what keeps the PIN's tiny keyspace safe: an
 * attacker without an enrolled device never reaches PIN verification at all.
 *
 * <p>Revocation checks sit on the hot request path (every device-pass request),
 * so they run through a small TTL cache: a revoked device stops authenticating
 * within {@link #REVOCATION_CACHE_TTL}, which the design accepts and documents.
 */
@Service
public class ChatDeviceService {

    static final Duration REVOCATION_CACHE_TTL = Duration.ofSeconds(60);

    private final ChatAccountDeviceRepository devices;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, CachedRevocation> revocationCache = new ConcurrentHashMap<>();

    public ChatDeviceService(ChatAccountDeviceRepository devices) {
        this.devices = devices;
    }

    /** Result of an enrollment: the plaintext secret exists only here, once. */
    public record Enrollment(String deviceSecret, boolean pinRequired) {}

    /* ---------------------------- enrollment ------------------------------- */

    /**
     * Enroll (or re-enroll) a device after a successful OTP or a lazy migration
     * of a pass-holding app. Re-enrolling rotates the secret and clears a prior
     * revocation — the caller has just proven phone control, which outranks the
     * old revocation. The per-device PIN policy survives re-enrollment.
     */
    @Transactional
    public Enrollment enroll(UUID accountId, String deviceId, String label) {
        String secret = newSecret();
        String hash = sha256Hex(secret);
        ChatAccountDeviceEntity row = devices.findByAccountIdAndDeviceId(accountId, deviceId)
                .orElse(null);
        if (row == null) {
            row = new ChatAccountDeviceEntity(accountId, deviceId, hash, clean(label));
        } else {
            row.setDeviceSecretHash(hash);
            if (clean(label) != null) row.setLabel(clean(label));
            row.unrevoke();
            row.touchLastSeen();
        }
        devices.save(row);
        revocationCache.remove(cacheKey(accountId, deviceId));
        return new Enrollment(secret, row.isPinRequired());
    }

    /* ------------------------------ unlock --------------------------------- */

    /**
     * Resolve the enrolled, unrevoked device a (deviceId, secret) pair proves.
     * The client-generated device id is not globally unique, so candidates fan
     * out by id and the secret hash picks the row. Constant-time comparison;
     * empty on any mismatch — the unlock endpoint collapses every failure mode
     * into one uniform 401.
     */
    @Transactional(readOnly = true)
    public Optional<ChatAccountDeviceEntity> resolveBySecret(String deviceId, String deviceSecret) {
        if (deviceId == null || deviceId.isBlank() || deviceSecret == null || deviceSecret.isBlank()) {
            return Optional.empty();
        }
        byte[] presented = sha256Hex(deviceSecret.trim()).getBytes(StandardCharsets.UTF_8);
        return devices.findByDeviceId(deviceId.trim()).stream()
                .filter(d -> MessageDigest.isEqual(
                        presented, d.getDeviceSecretHash().getBytes(StandardCharsets.UTF_8)))
                .filter(d -> !d.isRevoked())
                .findFirst();
    }

    @Transactional
    public void touchLastSeen(UUID accountId, String deviceId) {
        devices.findByAccountIdAndDeviceId(accountId, deviceId).ifPresent(d -> {
            d.touchLastSeen();
            devices.save(d);
        });
    }

    /* --------------------------- device manager ---------------------------- */

    @Transactional(readOnly = true)
    public List<ChatAccountDeviceEntity> list(UUID accountId) {
        return devices.findByAccountIdOrderByLastSeenAtDesc(accountId);
    }

    @Transactional
    public boolean revoke(UUID accountId, String deviceId) {
        var row = devices.findByAccountIdAndDeviceId(accountId, deviceId).orElse(null);
        if (row == null) return false;
        row.revoke();
        devices.save(row);
        revocationCache.remove(cacheKey(accountId, deviceId));
        return true;
    }

    @Transactional
    public void revokeAll(UUID accountId) {
        for (ChatAccountDeviceEntity d : devices.findByAccountIdOrderByLastSeenAtDesc(accountId)) {
            d.revoke();
            devices.save(d);
            revocationCache.remove(cacheKey(accountId, d.getDeviceId()));
        }
    }

    @Transactional
    public boolean setPinRequired(UUID accountId, String deviceId, boolean pinRequired) {
        var row = devices.findByAccountIdAndDeviceId(accountId, deviceId).orElse(null);
        if (row == null || row.isRevoked()) return false;
        row.setPinRequired(pinRequired);
        devices.save(row);
        return true;
    }

    /* --------------------------- trust check ------------------------------- */

    /**
     * Is this device revoked? The per-request check behind every device-pass
     * call. A device with no row is NOT revoked — passes minted before device
     * enrollment existed are grandfathered until natural expiry, exactly like
     * legacy passes without an {@code acct} claim.
     */
    public boolean isRevoked(UUID accountId, String deviceId) {
        if (accountId == null || deviceId == null || deviceId.isBlank()) return false;
        String key = cacheKey(accountId, deviceId);
        CachedRevocation cached = revocationCache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.revoked();
        }
        boolean revoked = devices.findByAccountIdAndDeviceId(accountId, deviceId)
                .map(ChatAccountDeviceEntity::isRevoked)
                .orElse(false);
        revocationCache.put(key, new CachedRevocation(revoked, Instant.now().plus(REVOCATION_CACHE_TTL)));
        return revoked;
    }

    /* ------------------------------ helpers -------------------------------- */

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String clean(String label) {
        if (label == null || label.isBlank()) return null;
        String trimmed = label.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    private static String cacheKey(UUID accountId, String deviceId) {
        return accountId + "|" + deviceId;
    }

    private record CachedRevocation(boolean revoked, Instant expiresAt) {}
}
