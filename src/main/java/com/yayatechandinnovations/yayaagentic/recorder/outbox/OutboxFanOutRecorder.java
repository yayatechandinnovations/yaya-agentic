package com.yayatechandinnovations.yayaagentic.recorder.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.persistence.RecorderOutboxEntity;
import com.yayatechandinnovations.yayaagentic.persistence.RecorderOutboxRepository;
import com.yayatechandinnovations.yayaagentic.recorder.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Decorator that delegates reads + writes to a primary recorder and, in the
 * same transaction, stages one outbox row per registered sink for each
 * recorded event. The {@code @Scheduled} dispatcher then publishes those
 * rows to sinks asynchronously. Wires from day one even with zero sinks
 * (design §16 q12).
 */
@Component
@Primary
public class OutboxFanOutRecorder implements ConversationRecorder {

    private final ConversationRecorder primary;
    private final List<RecorderSink> sinks;
    private final RecorderOutboxRepository outbox;
    private final ObjectMapper json;

    public OutboxFanOutRecorder(@org.springframework.beans.factory.annotation.Qualifier("postgresConversationRecorder")
                                ConversationRecorder primary,
                                List<RecorderSink> sinks,
                                RecorderOutboxRepository outbox,
                                ObjectMapper json) {
        this.primary = primary;
        this.sinks = sinks;
        this.outbox = outbox;
        this.json = json;
    }

    // ---- Write path: delegate + stage outbox rows in same tx -----------

    @Override
    @Transactional
    public void onSessionStarted(Session session, RecorderContext ctx) {
        primary.onSessionStarted(session, ctx);
        enqueue(OutboxEvent.Kind.SESSION_STARTED,
                session.tenant(),
                UUID.fromString(session.id().value()),
                null,
                () -> writeJson(new SessionStartedPayload(
                        session.id().value(),
                        session.tenant().value(),
                        session.profile().value(),
                        session.profile().version(),
                        session.principal() == null ? null : session.principal().subject(),
                        session.channel())));
    }

    @Override
    @Transactional
    public void onTurnRecorded(Ids.SessionId id, Turn turn, RecorderContext ctx) {
        primary.onTurnRecorded(id, turn, ctx);
        enqueue(OutboxEvent.Kind.TURN_RECORDED,
                ctx == null ? null : ctx.tenant(),
                UUID.fromString(id.value()),
                UUID.fromString(turn.id().value()),
                () -> writeJson(turn));
    }

    @Override
    @Transactional
    public void onSessionEnded(Ids.SessionId id, Operations.SessionEndContext ctx) {
        primary.onSessionEnded(id, ctx);
        enqueue(OutboxEvent.Kind.SESSION_ENDED,
                null,
                UUID.fromString(id.value()),
                null,
                () -> writeJson(ctx));
    }

    private void enqueue(OutboxEvent.Kind kind,
                         Ids.TenantId tenant,
                         UUID sessionId,
                         UUID turnId,
                         java.util.function.Supplier<String> payloadJson) {
        if (sinks.isEmpty()) return;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String payload = payloadJson.get();
        for (RecorderSink sink : sinks) {
            if (!sink.accepts(kind)) continue;
            outbox.save(new RecorderOutboxEntity(
                    tenant == null ? "default" : tenant.value(),
                    sessionId,
                    turnId,
                    sink.id(),
                    payload,
                    now));
        }
    }

    // ---- Read path: pure delegation -----------------------------------

    @Override
    public Optional<RecordedSession> loadSession(Ids.SessionId id, LoadOptions opts) {
        return primary.loadSession(id, opts);
    }

    @Override
    public List<Turn> loadTurns(Ids.SessionId id, TurnRange range) {
        return primary.loadTurns(id, range);
    }

    @Override
    public Page<SessionSummary> searchSessions(SessionQuery query) {
        return primary.searchSessions(query);
    }

    @Override
    public Operations.RedactionResult redact(Ids.SessionId id, Operations.RedactionRequest req) {
        return primary.redact(id, req);
    }

    @Override
    public void deleteSession(Ids.SessionId id, Operations.DeletionRequest req) {
        primary.deleteSession(id, req);
    }

    @Override
    public Stream<Operations.SessionExport> exportForPrincipal(Principal p, Operations.ExportRequest req) {
        return primary.exportForPrincipal(p, req);
    }

    @Override
    public void archive(Ids.SessionId id, Operations.ArchivePolicy policy) {
        primary.archive(id, policy);
    }

    @Override
    public RecorderCapabilities capabilities() {
        return primary.capabilities();
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode outbox payload", ex);
        }
    }

    private record SessionStartedPayload(String sessionId, String tenant, String profileId,
                                         int profileVersion, String principalSubject, String channel) {}
}
