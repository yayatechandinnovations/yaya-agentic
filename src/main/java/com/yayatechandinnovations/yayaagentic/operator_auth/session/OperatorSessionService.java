package com.yayatechandinnovations.yayaagentic.operator_auth.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;
import com.yayatechandinnovations.yayaagentic.persistence.OperatorSessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OperatorSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side session store for console operators. Cookies carry a 256-bit
 * random opaque token; the DB stores only its hex-SHA-256 so a leaked DB
 * snapshot can't grant session takeover.
 *
 * <p>Sliding extension: every {@link #lookup} bumps {@code expires_at} by
 * the configured sliding window, capped at the absolute TTL. See design
 * §6 and §11.</p>
 */
@Service
public class OperatorSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();

    private final OperatorSessionRepository repo;
    private final ObjectMapper json;
    private final Duration ttl;
    private final Duration slidingWindow;

    public OperatorSessionService(OperatorSessionRepository repo,
                                  ObjectMapper json,
                                  YayaAgenticProperties props) {
        this.repo = repo;
        this.json = json;
        var sess = props.operatorAuth() == null ? null : props.operatorAuth().session();
        this.ttl = sess == null || sess.ttl() == null ? Duration.ofHours(8) : sess.ttl();
        this.slidingWindow = sess == null || sess.slidingWindow() == null
                ? Duration.ofHours(1) : sess.slidingWindow();
    }

    @Transactional
    public Issued create(Operator operator, String clientIp, String userAgent) {
        String raw = generateToken();
        String idHash = sha256Hex(raw);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expires = now.plusSeconds(slidingWindow.toSeconds());

        repo.save(new OperatorSessionEntity(
                idHash,
                operator.subject(),
                operator.displayName(),
                operator.source().name(),
                now,
                expires,
                clientIp,
                userAgent,
                serializeAttributes(operator.attributes())));
        return new Issued(raw, expires);
    }

    @Transactional
    public Optional<Loaded> lookup(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        String idHash = sha256Hex(rawToken);
        Optional<OperatorSessionEntity> maybe = repo.findById(idHash);
        if (maybe.isEmpty()) return Optional.empty();
        OperatorSessionEntity e = maybe.get();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (e.getExpiresAt().isBefore(now)) {
            repo.deleteById(idHash);
            return Optional.empty();
        }
        // Sliding extension capped at the absolute TTL from createdAt.
        OffsetDateTime newExpiry = now.plusSeconds(slidingWindow.toSeconds());
        OffsetDateTime ceiling = e.getCreatedAt().plusSeconds(ttl.toSeconds());
        if (newExpiry.isAfter(ceiling)) newExpiry = ceiling;
        if (newExpiry.isAfter(e.getExpiresAt())) {
            e.setExpiresAt(newExpiry);
            e.setLastSeenAt(now);
        }
        return Optional.of(new Loaded(
                new Operator(
                        e.getOperatorSubject(),
                        e.getOperatorDisplay(),
                        Operator.Source.valueOf(e.getSource()),
                        Map.of(), // attributes not rehydrated in v1 — phase 3 reads JSONB if needed
                        e.getCreatedAt().toInstant()),
                e.getExpiresAt()));
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        repo.deleteById(sha256Hex(rawToken));
    }

    /** What the controller hands to the browser. */
    public record Issued(String rawToken, OffsetDateTime expiresAt) {}

    /** What the filter sees on every authenticated request. */
    public record Loaded(Operator operator, OffsetDateTime expiresAt) {}

    private String generateToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return URL_ENC.encodeToString(buf);
    }

    private String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String serializeAttributes(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) return null;
        try {
            return json.writeValueAsString(attrs);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
