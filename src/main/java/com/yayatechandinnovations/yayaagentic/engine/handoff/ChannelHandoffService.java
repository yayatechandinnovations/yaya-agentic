package com.yayatechandinnovations.yayaagentic.engine.handoff;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.TurnChannel;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.ActionNotPermittedException;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.persistence.SessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SessionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TurnEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TurnRepository;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.TurnRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds the user's open conversation on another channel and copies its
 * context into the one they are on now. Reads rows the way the sessions API
 * does; the copy itself goes through the engine, so the target's turn
 * counter and its recorder stay the engine's.
 */
@Service
public class ChannelHandoffService {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelHandoffService.class);

    /** The WhatsApp idle policy: an older thread is not "where the user was". */
    public static final Duration ELSEWHERE_WINDOW = Duration.ofHours(24);
    /** An expired source is fine (its summary is stored); a week-old one is history. */
    public static final Duration SOURCE_MAX_AGE = Duration.ofDays(7);
    public static final int PREVIEW_CHARS = 160;
    private static final int CANDIDATES = 5;

    private final SessionRepository sessions;
    private final TurnRepository turns;
    private final ConversationRecorder recorder;
    private final WorkingMemory workingMemory;
    private final ConversationEngine engine;
    private final Authenticator authenticator;

    public ChannelHandoffService(SessionRepository sessions,
                                 TurnRepository turns,
                                 @Qualifier("postgresConversationRecorder") ConversationRecorder recorder,
                                 WorkingMemory workingMemory,
                                 ConversationEngine engine,
                                 Authenticator authenticator) {
        this.sessions = sessions;
        this.turns = turns;
        this.recorder = recorder;
        this.workingMemory = workingMemory;
        this.engine = engine;
        this.authenticator = authenticator;
    }

    /** Another open conversation of this user, same profile, different channel, recent. */
    public record Elsewhere(SessionEntity session, Instant lastActivityAt, int turns,
                            String userPreview, String assistantPreview, String pending) {
        public String channel() {
            return channelLabel(session);
        }
    }

    public record Result(Ids.SessionId fromSessionId, String fromChannel, List<Turn> carried,
                         HandoffBlock block) {}

    public Optional<Elsewhere> elsewhere(String tenantId, String subject, String profileId, String channel) {
        return elsewhere(tenantId, subject, profileId, channel, Instant.now());
    }

    Optional<Elsewhere> elsewhere(String tenantId, String subject, String profileId, String channel, Instant now) {
        if (channel == null || channel.isBlank()) return Optional.empty();
        return newestWithin(sessions.findOpenElsewhere(tenantId, subject, profileId, channel,
                PageRequest.of(0, CANDIDATES)), now.minus(ELSEWHERE_WINDOW))
                .map(found -> describe(found.row(), found.lastTurnAt(), now));
    }

    /**
     * The user's open session born on {@code channel} with a turn in the last
     * {@code within} — what another channel's greeting offers to continue.
     */
    public Optional<Ids.SessionId> recentOn(String tenantId, String subject, String profileId, String channel,
                                            Duration within) {
        if (subject == null || subject.isBlank()) return Optional.empty();
        return newestWithin(sessions.findReopenable(tenantId, subject, profileId, channel,
                PageRequest.of(0, CANDIDATES)), Instant.now().minus(within))
                .map(found -> new Ids.SessionId(found.row().getId().toString()));
    }

    /**
     * Copies the context of {@code sourceId} into {@code target}, which the
     * caller already admitted as its owner's live session. {@code auth} must
     * carry that owner, verified: the engine rehydrates the target with it.
     */
    public Result perform(Ids.SessionId targetId, Ids.SessionId sourceId, AuthContext auth) {
        SessionEntity target = lookup(targetId);
        if (target == null) {
            throw new HandoffException(HandoffException.SESSION_NOT_FOUND, "no such target conversation");
        }
        return perform(target, sourceId, auth);
    }

    public Result perform(SessionEntity target, Ids.SessionId sourceId, AuthContext auth) {
        if (target == null || target.getEndedAt() != null) {
            throw new HandoffException(HandoffException.NOT_ALLOWED, "the target conversation is not live");
        }
        SessionEntity source = lookup(sourceId);
        if (source == null || !Objects.equals(source.getTenantId(), target.getTenantId())) {
            throw new HandoffException(HandoffException.SESSION_NOT_FOUND, "no such source conversation");
        }
        // Both conversations must belong to an identified user: two anonymous
        // sessions share a null subject, which is not the same person.
        if (isBlank(source.getPrincipalSubject()) || isBlank(target.getPrincipalSubject())) {
            throw new HandoffException(HandoffException.NOT_ALLOWED,
                    "a hand-off needs two conversations of a signed-in user");
        }
        if (source.getId().equals(target.getId())
                || !Objects.equals(source.getPrincipalSubject(), target.getPrincipalSubject())
                || !Objects.equals(source.getProfileId(), target.getProfileId())
                || TurnChannel.isReserved(source.getChannel())) {
            throw new HandoffException(HandoffException.NOT_ALLOWED,
                    "the source conversation is not this user's on this profile");
        }
        // The caller must be that user, verified on this request — not inferred
        // from the row it is pointing at.
        String owner = verifiedSubject(auth);
        if (owner == null || !owner.equals(target.getPrincipalSubject())) {
            throw new HandoffException(HandoffException.NOT_ALLOWED, "not the verified owner of the target conversation");
        }

        Ids.SessionId targetId = new Ids.SessionId(target.getId().toString());
        List<Turn> targetTurns = recorder.loadTurns(targetId, TurnRange.all());
        if (targetTurns.stream().anyMatch(ChannelHandoffService::isHandoff)) {
            throw new HandoffException(HandoffException.EXISTS, "this conversation already received a hand-off");
        }

        List<Turn> sourceTurns = recorder.loadTurns(sourceId, TurnRange.all());
        Instant lastActivity = sourceTurns.isEmpty()
                ? source.getCreatedAt().toInstant() : sourceTurns.get(sourceTurns.size() - 1).createdAt();
        if (lastActivity == null || lastActivity.isBefore(Instant.now().minus(SOURCE_MAX_AGE))) {
            throw new HandoffException(HandoffException.SOURCE_TOO_OLD, "the source conversation is too old");
        }

        String fromChannel = channelLabel(source);
        HandoffBlock block = HandoffBlock.build(fromChannel, source.getSummaryText(), sourceTurns);
        Map<String, Object> handoff = new LinkedHashMap<>();
        handoff.put("fromSessionId", sourceId.value());
        handoff.put("fromChannel", fromChannel);
        handoff.put("throughTurnIdx", block.throughTurnIdx());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("origin", HandoffBlock.ORIGIN);
        meta.put("handoff", handoff);

        try {
            // The target may have fallen out of the engine (a restart): bring it
            // back first, or the turn would be dropped and the counter would drift.
            engine.resume(targetId, auth);
        } catch (ActionNotPermittedException denied) {
            throw new HandoffException(HandoffException.NOT_ALLOWED, denied.getMessage());
        } catch (IllegalStateException gone) {
            throw new HandoffException(HandoffException.SESSION_NOT_FOUND, gone.getMessage());
        }
        if (!engine.appendSystemTurn(targetId, block.content(), meta)) {
            throw new IllegalStateException("hand-off turn was not recorded for " + targetId.value());
        }
        LOG.info("hand-off: {} turns from {} ({}) into {}", block.carried().size(), sourceId.value(),
                fromChannel, targetId.value());
        return new Result(sourceId, fromChannel, block.carried(), block);
    }

    /**
     * The subject this request proves: the principal the API layer already
     * verified, or what the authenticator chain makes of the channel headers
     * (the WhatsApp worker's signed-webhook identity). Nothing provable → null.
     */
    private String verifiedSubject(AuthContext auth) {
        if (auth == null) return null;
        if (auth.alreadyVerified() != null && auth.alreadyVerified().isPresent()) {
            return auth.alreadyVerified().get().subject();
        }
        if (authenticator == null) return null;
        try {
            var principal = authenticator.authenticate(auth);
            return principal == null || isBlank(principal.subject()) ? null : principal.subject();
        } catch (AuthenticationException | IllegalStateException e) {
            LOG.debug("hand-off caller not verified: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static boolean isHandoff(Turn t) {
        return t.role() == Turn.Role.SYSTEM && t.metadata() != null
                && HandoffBlock.ORIGIN.equals(t.metadata().get("origin"));
    }

    /** The label a client gave the session, {@code app} when it gave none. */
    public static String channelLabel(SessionEntity row) {
        return row == null || row.getChannel() == null || row.getChannel().isBlank() ? "app" : row.getChannel();
    }

    private record Found(SessionEntity row, Instant lastTurnAt) {}

    private Optional<Found> newestWithin(List<SessionEntity> rows, Instant since) {
        Found best = null;
        for (SessionEntity row : rows) {
            Instant last = turns.findFirstBySessionIdOrderByIdxDesc(row.getId())
                    .map(t -> t.getCreatedAt().toInstant()).orElse(null);
            if (last == null || last.isBefore(since)) continue;
            if (best == null || last.isAfter(best.lastTurnAt())) best = new Found(row, last);
        }
        return Optional.ofNullable(best);
    }

    private Elsewhere describe(SessionEntity row, Instant lastTurnAt, Instant now) {
        Ids.SessionId sid = new Ids.SessionId(row.getId().toString());
        List<Turn> all = recorder.loadTurns(sid, TurnRange.all());
        String user = null;
        String assistant = null;
        int count = 0;
        for (Turn t : all) {
            String text = t.content() == null ? "" : t.content().strip();
            if (t.role() == Turn.Role.USER && !text.isEmpty()) {
                user = text;
                count++;
            } else if (t.role() == Turn.Role.ASSISTANT && !text.isEmpty()) {
                assistant = text;
                count++;
            }
        }
        String pending = null;
        try {
            pending = PendingFrame.of(workingMemory.get(sid), now);
        } catch (RuntimeException e) {
            LOG.debug("pending state unavailable for {}: {}", sid.value(), e.toString());
        }
        return new Elsewhere(row, lastTurnAt, count, cut(user), cut(assistant), pending);
    }

    static String cut(String text) {
        if (text == null) return null;
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS - 1) + "…";
    }

    private SessionEntity lookup(Ids.SessionId id) {
        if (id == null || id.value() == null) return null;
        try {
            return sessions.findById(UUID.fromString(id.value())).orElse(null);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
