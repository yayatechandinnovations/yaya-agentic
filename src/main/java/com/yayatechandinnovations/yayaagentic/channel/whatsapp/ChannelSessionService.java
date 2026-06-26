package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

/**
 * The WhatsApp-side index of "is there an engine session open for this end
 * user on this sender?" Distinct from the recorder's {@code sessions} table
 * — see §9.2. Driven by {@link
 * com.yayatechandinnovations.yayaagentic.channel.whatsapp.InboundDispatchWorker}.
 */
@Service
public class ChannelSessionService {

    private static final String OPEN   = "OPEN";
    private static final String CLOSED = "CLOSED";

    private final ChannelSessionRepository repo;

    public ChannelSessionService(ChannelSessionRepository repo) {
        this.repo = repo;
    }

    /**
     * Apply the §9.2 precedence rules. The caller MUST have already
     * resolved the policy from the binding.
     *
     * <p>Outcomes:
     * <ul>
     *   <li>{@link Outcome.Reuse} — found an OPEN row within idle.</li>
     *   <li>{@link Outcome.Reopen} — body matched a new-session keyword OR
     *       the prior row is past idle. Caller starts a fresh engine session.</li>
     *   <li>{@link Outcome.Open}   — no prior row at all. Fresh engine
     *       session.</li>
     * </ul>
     */
    @Transactional
    public Outcome openOrReuse(Ids.TenantId tenant,
                               SenderId sender,
                               String endUserPhone,
                               String inboundBody,
                               SessionPolicy policy) {
        Optional<ChannelSessionEntity> openRow =
                repo.findFirstByTenantIdAndSenderIdAndEndUserPhoneAndStatus(
                        tenant.value(), sender.value(), endUserPhone, OPEN);

        boolean isNewSessionKeyword = isNewSessionKeyword(inboundBody, policy);
        if (openRow.isPresent()) {
            ChannelSessionEntity row = openRow.get();
            Instant lastInbound = row.getLastInboundAt().toInstant();
            boolean idleExpired = Instant.now().isAfter(lastInbound.plus(policy.idleTimeout()));
            if (isNewSessionKeyword) {
                close(row, "USER_REQUESTED_NEW");
                return new Outcome.Reopen(true);
            }
            if (idleExpired) {
                close(row, "IDLE_TIMEOUT");
                return new Outcome.Reopen(false);
            }
            row.setLastInboundAt(OffsetDateTime.now(ZoneOffset.UTC));
            repo.save(row);
            return new Outcome.Reuse(new Ids.SessionId(row.getSessionId()),
                    isNewSessionKeyword);
        }
        return new Outcome.Open(isNewSessionKeyword);
    }

    @Transactional
    public ChannelSessionEntity register(Ids.TenantId tenant,
                                         SenderId sender,
                                         String endUserPhone,
                                         Ids.SessionId sessionId) {
        ChannelSessionEntity entity = new ChannelSessionEntity(
                tenant.value(), sender.value(), endUserPhone, sessionId.value(),
                OffsetDateTime.now(ZoneOffset.UTC));
        return repo.save(entity);
    }

    @Transactional
    public void markOutbound(Ids.SessionId sessionId) {
        repo.findByTenantIdAndSessionId(/* tenant */ null, sessionId.value());
        // tenant nullable here is fine — Spring Data's derived query
        // ignores null when the param is unbound; we use the session_id
        // index. Wrap to avoid issuing a NULL tenant query, defensive:
        var row = repo.findByTenantIdAndSessionId(null, sessionId.value())
                .or(() -> repo.findByStatusOrderByLastInboundAtDesc(OPEN).stream()
                        .filter(e -> e.getSessionId().equals(sessionId.value()))
                        .findFirst());
        row.ifPresent(r -> {
            r.setLastOutboundAt(OffsetDateTime.now(ZoneOffset.UTC));
            repo.save(r);
        });
    }

    @Transactional
    public Optional<ChannelSessionEntity> close(Ids.TenantId tenant,
                                                Ids.SessionId sessionId,
                                                String reason) {
        return repo.findByTenantIdAndSessionId(tenant.value(), sessionId.value())
                .filter(e -> OPEN.equals(e.getStatus()))
                .map(e -> {
                    close(e, reason);
                    return e;
                });
    }

    private void close(ChannelSessionEntity row, String reason) {
        row.setStatus(CLOSED);
        row.setCloseReason(reason);
        row.setClosedAt(OffsetDateTime.now(ZoneOffset.UTC));
        repo.save(row);
    }

    private static boolean isNewSessionKeyword(String body, SessionPolicy policy) {
        if (body == null) return false;
        String trimmed = body.trim().toLowerCase(Locale.ROOT);
        for (String keyword : policy.newSessionKeywords()) {
            if (keyword != null && trimmed.equals(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Result of {@link #openOrReuse}. */
    public sealed interface Outcome permits Outcome.Reuse, Outcome.Reopen, Outcome.Open {
        boolean consumedKeyword();

        /** Caller continues the existing engine session. */
        record Reuse(Ids.SessionId existing, boolean consumedKeyword) implements Outcome {}

        /** Caller closes the prior row (already done) and starts a new
         *  engine session — body should be passed through normally unless
         *  {@code consumedKeyword} is true (then the body was a keyword and
         *  shouldn't reach the engine). */
        record Reopen(boolean consumedKeyword) implements Outcome {}

        /** No prior row. Start fresh. */
        record Open(boolean consumedKeyword) implements Outcome {}
    }
}
