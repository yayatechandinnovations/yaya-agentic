package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.SessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * AES-256-GCM persistence for {@code sessions.auth_token_*}. Writes the
 * resolver-minted JWT in two columns ({@code auth_token_ciphertext} +
 * {@code auth_token_iv}) via {@link JwtSecretCipher}; wipes both on
 * session end. The Redis cache survives across sessions, so wiping the
 * session row is a "this conversation is done" signal, not a "the user
 * needs to re-authenticate from scratch" signal.
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §9 (steps
 * 3e, 6).
 */
@Component
public class SessionAuthTokenStore {

    private static final Logger log = LoggerFactory.getLogger(SessionAuthTokenStore.class);

    private final SessionRepository sessions;
    private final JwtSecretCipher cipher;

    public SessionAuthTokenStore(SessionRepository sessions, JwtSecretCipher cipher) {
        this.sessions = sessions;
        this.cipher = cipher;
    }

    /** Whether {@link #persist} can actually write a token to disk. False when
     *  the envelope cipher key isn't configured — surfaced to the inspector so
     *  an operator sees "cipher not configured" rather than a silent no-store. */
    public boolean isPersistable() {
        return cipher.isConfigured();
    }

    /** Encrypt + persist a JWT to the session row. No-op when the session
     *  is gone (race against session end) or when the cipher isn't
     *  configured (operator misconfig — surfaces in the warning log). */
    @Transactional
    public void persist(Ids.SessionId session, String jwt) {
        if (session == null || jwt == null || jwt.isBlank()) return;
        if (!cipher.isConfigured()) {
            log.warn("session auth-token cipher not configured — JWT for session={} "
                            + "will not be persisted to disk (cache still holds it)",
                    session.value());
            return;
        }
        Optional<SessionEntity> row = findById(session);
        if (row.isEmpty()) return;
        JwtSecretCipher.Encrypted enc = cipher.encrypt(jwt);
        SessionEntity entity = row.get();
        entity.setAuthTokenCiphertext(enc.ciphertext());
        entity.setAuthTokenIv(enc.iv());
        sessions.save(entity);
    }

    /** Read the encrypted JWT off the session row. Returns empty when the
     *  row has no token or the cipher isn't configured. */
    public Optional<String> read(Ids.SessionId session) {
        if (session == null || !cipher.isConfigured()) return Optional.empty();
        return findById(session).flatMap(e -> {
            if (e.getAuthTokenCiphertext() == null) return Optional.empty();
            try {
                return Optional.of(cipher.decrypt(
                        e.getAuthTokenCiphertext(), e.getAuthTokenIv()));
            } catch (JwtSecretCipher.JwtSecretCipherException ex) {
                log.warn("session auth-token decrypt failed (key rotation?): {}",
                        ex.getMessage());
                return Optional.empty();
            }
        });
    }

    /** Wipe both ciphertext + IV. Called from
     *  {@code PostgresConversationRecorder.onSessionEnded} so the row's
     *  ended_at + JWT-null transitions land in the same transaction. */
    @Transactional
    public void wipe(Ids.SessionId session) {
        if (session == null) return;
        findById(session).ifPresent(e -> {
            if (e.getAuthTokenCiphertext() == null && e.getAuthTokenIv() == null) return;
            e.setAuthTokenCiphertext(null);
            e.setAuthTokenIv(null);
            sessions.save(e);
        });
    }

    private Optional<SessionEntity> findById(Ids.SessionId session) {
        try {
            return sessions.findById(UUID.fromString(session.value()));
        } catch (IllegalArgumentException e) {
            // Non-UUID session id (some channel-only sessions never go
            // through the recorder schema). Nothing to persist.
            return Optional.empty();
        }
    }
}
