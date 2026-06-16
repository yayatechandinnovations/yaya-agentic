package com.yayatechandinnovations.yayaagentic.recorder.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.*;
import com.yayatechandinnovations.yayaagentic.persistence.SessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SessionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TurnEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TurnRepository;
import com.yayatechandinnovations.yayaagentic.recorder.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * M1 default recorder. Reads + writes against {@code sessions} and {@code turns}.
 * Compliance ops: delete is real; redact masks the turn's textual content in
 * place and leaves a tombstone metadata entry; archive + export remain M5.
 * <p>
 * Not annotated {@link org.springframework.context.annotation.Primary} on
 * purpose — {@code OutboxFanOutRecorder} wraps this and is the primary bean
 * the engine sees through {@code RecorderRouter}.
 */
@Component("postgresConversationRecorder")
public class PostgresConversationRecorder implements ConversationRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresConversationRecorder.class);

    private static final TypeReference<List<Turn.ToolCall>> TOOL_CALLS = new TypeReference<>() {};
    private static final TypeReference<List<Turn.ToolResult>> TOOL_RESULTS = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> STRING_MAP = new TypeReference<>() {};

    private final SessionRepository sessions;
    private final TurnRepository turns;
    private final ObjectMapper json;
    private final ObjectProvider<ConversationSummarizer> summarizerProvider;
    private final YayaAgenticProperties.Memory memoryConfig;
    private final TransactionTemplate rotationReadTx;
    private final TransactionTemplate rotationWriteTx;

    /** Per-session lock: one rotation at a time even if turns arrive faster. */
    private final ConcurrentHashMap<UUID, Boolean> rotationsInFlight = new ConcurrentHashMap<>();

    public PostgresConversationRecorder(SessionRepository sessions,
                                        TurnRepository turns,
                                        ObjectMapper json,
                                        ObjectProvider<ConversationSummarizer> summarizerProvider,
                                        YayaAgenticProperties props,
                                        PlatformTransactionManager txManager) {
        this.sessions = sessions;
        this.turns = turns;
        this.json = json;
        this.summarizerProvider = summarizerProvider;
        this.memoryConfig = props.session() == null || props.session().memory() == null
                ? YayaAgenticProperties.Memory.defaults()
                : props.session().memory();
        // Two separate transactions for rotation: a fast read, then the LLM
        // call OUTSIDE any tx, then a fast write. Both REQUIRES_NEW because
        // we fire after the outer onTurnRecorded commit (afterCommit), so no
        // ambient transaction is expected — but be explicit.
        this.rotationReadTx = new TransactionTemplate(txManager);
        this.rotationReadTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.rotationReadTx.setReadOnly(true);
        this.rotationWriteTx = new TransactionTemplate(txManager);
        this.rotationWriteTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---- Write path -----------------------------------------------------

    @Override
    @Transactional
    public void onSessionStarted(Session session, RecorderContext ctx) {
        SessionEntity entity = new SessionEntity(
                UUID.fromString(session.id().value()),
                session.tenant().value(),
                session.principal() == null ? null : session.principal().subject(),
                session.profile().value(),
                session.profile().version(),
                session.channel(),
                session.createdAt() == null
                        ? OffsetDateTime.now(ZoneOffset.UTC)
                        : session.createdAt().atOffset(ZoneOffset.UTC));
        sessions.save(entity);
    }

    @Override
    @Transactional
    public void onTurnRecorded(Ids.SessionId id, Turn turn, RecorderContext ctx) {
        UUID sessionUuid = UUID.fromString(id.value());
        TurnEntity entity = new TurnEntity(
                UUID.fromString(turn.id().value()),
                sessionUuid,
                turn.index(),
                turn.role().name(),
                writeContentJson(turn.content(), turn.metadata()),
                turn.createdAt() == null
                        ? OffsetDateTime.now(ZoneOffset.UTC)
                        : turn.createdAt().atOffset(ZoneOffset.UTC));
        entity.setToolCallsJson(writeJson(turn.toolCalls()));
        entity.setToolResultsJson(writeJson(turn.toolResults()));
        entity.setRetrievedIdsJson(writeJson(turn.retrievedChunkIds()));
        if (turn.modelInfo() != null) {
            entity.setModel(turn.modelInfo().provider() + "/" + turn.modelInfo().model());
            entity.setTokensIn(turn.modelInfo().tokensIn());
            entity.setTokensOut(turn.modelInfo().tokensOut());
        }
        turns.save(entity);

        scheduleRotationAfterCommit(sessionUuid);
    }

    /**
     * Schedule summarization to fire post-commit, OUTSIDE the write transaction.
     * Holding row locks across an LLM call would exhaust the connection pool
     * and inflate p99. The user turn write commits regardless of summarization
     * outcome, so a failing summarizer never breaks a user-visible interaction.
     */
    private void scheduleRotationAfterCommit(UUID sessionUuid) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    rotateIfNeeded(sessionUuid);
                }
            });
        } else {
            // No active tx (test path or callers wiring direct, no @Transactional
            // ancestor). Run inline — tests can swap in a synchronous summarizer.
            rotateIfNeeded(sessionUuid);
        }
    }

    /**
     * Post-commit rotation. Reads the unsummarized tail; if it exceeds
     * {@code rotationThreshold}, hands the oldest {@code (count - tailWindow)}
     * turns to the {@link ConversationSummarizer} and persists the result in
     * a {@code REQUIRES_NEW} transaction.
     */
    private void rotateIfNeeded(UUID sessionUuid) {
        if (rotationsInFlight.putIfAbsent(sessionUuid, Boolean.TRUE) != null) {
            return;
        }
        try {
            ConversationSummarizer summarizer = summarizerProvider.getIfAvailable();
            if (summarizer == null) {
                LOG.debug("no ConversationSummarizer bean; skipping rotation for {}", sessionUuid);
                return;
            }
            RotationPlan plan = rotationReadTx.execute(status -> readRotationPlan(sessionUuid));
            if (plan == null) return;

            ConversationSummarizer.SummarizationContext sctx =
                    new ConversationSummarizer.SummarizationContext(
                            0,
                            Map.of("priorSummary", plan.priorSummary == null ? "" : plan.priorSummary));
            ConversationSummarizer.SummarizedTurn out = summarizer.summarize(plan.window, sctx);
            if (out == null || out.text() == null || out.text().isBlank()) return;

            persistSummary(sessionUuid, out.text(), plan.newThroughIdx);
        } catch (RuntimeException e) {
            LOG.warn("summary rotation failed for session {}: {}", sessionUuid, e.getMessage());
        } finally {
            rotationsInFlight.remove(sessionUuid);
        }
    }

    /** Decision + window materialization under a read-only tx. Returns null when nothing to rotate. */
    private RotationPlan readRotationPlan(UUID sessionUuid) {
        SessionEntity session = sessions.findById(sessionUuid).orElse(null);
        if (session == null) return null;
        Integer through = session.getSummaryThroughTurnIdx();
        List<TurnEntity> tail = turns.findBySessionIdOrderByIdxAsc(sessionUuid).stream()
                .filter(t -> through == null || t.getIdx() > through)
                .toList();
        if (tail.size() <= memoryConfig.rotationThreshold()) return null;
        int toFold = tail.size() - memoryConfig.tailWindow();
        List<TurnEntity> window = tail.subList(0, toFold);
        int newThrough = window.get(window.size() - 1).getIdx();
        List<Turn> domainWindow = window.stream().map(this::toTurn).toList();
        return new RotationPlan(domainWindow, session.getSummaryText(), newThrough);
    }

    private record RotationPlan(List<Turn> window, String priorSummary, int newThroughIdx) {}

    private void persistSummary(UUID sessionUuid, String text, int throughIdx) {
        rotationWriteTx.executeWithoutResult(status -> sessions.findById(sessionUuid).ifPresent(s -> {
            s.setSummaryText(text);
            s.setSummaryThroughTurnIdx(throughIdx);
            s.setSummaryUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }));
    }

    @Override
    @Transactional
    public void onSessionEnded(Ids.SessionId id, Operations.SessionEndContext ctx) {
        sessions.findById(UUID.fromString(id.value())).ifPresent(entity -> {
            OffsetDateTime endedAt = ctx == null || ctx.endedAt() == null
                    ? OffsetDateTime.now(ZoneOffset.UTC)
                    : ctx.endedAt().atOffset(ZoneOffset.UTC);
            entity.setEndedAt(endedAt);
        });
    }

    // ---- Read path ------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<RecordedSession> loadSession(Ids.SessionId id, LoadOptions opts) {
        UUID uuid = UUID.fromString(id.value());
        return sessions.findById(uuid).map(s -> {
            if (opts == null || !opts.summarized()) {
                return new RecordedSession(toSession(s), readTurns(uuid));
            }
            Integer through = s.getSummaryThroughTurnIdx();
            List<Turn> tail = turns.findBySessionIdOrderByIdxAsc(uuid).stream()
                    .filter(t -> through == null || t.getIdx() > through)
                    .map(this::toTurn)
                    .toList();
            int max = Math.max(1, opts.maxTurns());
            if (tail.size() > max) {
                tail = tail.subList(tail.size() - max, tail.size());
            }
            RecordedSession.Summary summary = (s.getSummaryText() != null && through != null)
                    ? new RecordedSession.Summary(s.getSummaryText(), through)
                    : null;
            return new RecordedSession(toSession(s), tail, summary);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<Turn> loadTurns(Ids.SessionId id, TurnRange range) {
        List<Turn> all = readTurns(UUID.fromString(id.value()));
        if (range == null || range.limit() == null || range.limit() >= all.size()) {
            return all;
        }
        int limit = Math.max(0, range.limit());
        return all.subList(all.size() - limit, all.size());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SessionSummary> searchSessions(SessionQuery query) {
        PageRequest pr = PageRequest.of(Math.max(0, query.page()), Math.max(1, query.pageSize()));
        var page = sessions.search(
                query.tenant().value(),
                query.principalSubject().orElse(null),
                query.profile().map(Ids.ProfileId::value).orElse(null),
                query.from().map(i -> i.atOffset(ZoneOffset.UTC)).orElse(null),
                query.to().map(i -> i.atOffset(ZoneOffset.UTC)).orElse(null),
                pr);
        List<SessionSummary> items = page.getContent().stream().map(this::toSummary).toList();
        return new Page<>(items, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    // ---- Compliance / lifecycle -----------------------------------------

    @Override
    @Transactional
    public Operations.RedactionResult redact(Ids.SessionId id, Operations.RedactionRequest req) {
        UUID sessionUuid = UUID.fromString(id.value());
        List<TurnEntity> all = turns.findBySessionIdOrderByIdxAsc(sessionUuid);
        int affected = 0;
        int fields = 0;
        for (TurnEntity t : all) {
            Map<String, Object> content = readMap(t.getContentJson());
            boolean changed = false;
            for (String selector : req.jsonPathSelectors()) {
                // M1 supports only top-level $.<field> selectors. Full
                // JSONPath comes with the admin redact UX in M3.
                if (selector != null && selector.startsWith("$.")) {
                    String key = selector.substring(2);
                    if (content.containsKey(key)) {
                        content.put(key, "[REDACTED]");
                        changed = true;
                        fields++;
                    }
                }
            }
            if (changed) {
                Map<String, Object> tombstone = new LinkedHashMap<>(content);
                Map<String, Object> meta = readMap(json.convertValue(content.getOrDefault("metadata", Map.of()),
                        new TypeReference<Map<String, Object>>() {}));
                meta.put("redacted_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
                meta.put("redaction_reason", req.reason());
                meta.put("redaction_operator", req.operatorSubject());
                tombstone.put("metadata", meta);
                t.setContentJson(writeJson(tombstone));
                affected++;
            }
        }
        return new Operations.RedactionResult(affected, fields, OffsetDateTime.now(ZoneOffset.UTC).toInstant());
    }

    @Override
    @Transactional
    public void deleteSession(Ids.SessionId id, Operations.DeletionRequest req) {
        // Cascades to turns via the FK ON DELETE CASCADE.
        sessions.deleteById(UUID.fromString(id.value()));
    }

    @Override
    public Stream<Operations.SessionExport> exportForPrincipal(Principal p, Operations.ExportRequest req) {
        throw new UnsupportedOperationException("principal export is implemented in M5");
    }

    @Override
    public void archive(Ids.SessionId id, Operations.ArchivePolicy policy) {
        throw new UnsupportedOperationException("archive is implemented by S3ColdRecorder in M5");
    }

    @Override
    public RecorderCapabilities capabilities() {
        return new RecorderCapabilities(
                true, true, false, false,
                RecorderCapabilities.Durability.STRONG,
                Duration.ofMillis(50));
    }

    // ---- Mapping helpers ------------------------------------------------

    private List<Turn> readTurns(UUID sessionUuid) {
        return turns.findBySessionIdOrderByIdxAsc(sessionUuid).stream()
                .map(this::toTurn).toList();
    }

    private Turn toTurn(TurnEntity e) {
        Map<String, Object> content = readMap(e.getContentJson());
        String text = String.valueOf(content.getOrDefault("text", ""));
        Map<String, Object> metadata = readMap(json.convertValue(content.getOrDefault("metadata", Map.of()),
                new TypeReference<Map<String, Object>>() {}));
        return new Turn(
                new Ids.TurnId(e.getId().toString()),
                new Ids.SessionId(e.getSessionId().toString()),
                e.getIdx(),
                Turn.Role.valueOf(e.getRole()),
                text,
                readJson(e.getToolCallsJson(), TOOL_CALLS),
                readJson(e.getToolResultsJson(), TOOL_RESULTS),
                readJson(e.getRetrievedIdsJson(), STRING_LIST),
                e.getModel() == null ? null
                        : new Turn.ModelInfo("recorded", e.getModel(), e.getTokensIn(), e.getTokensOut()),
                metadata,
                e.getCreatedAt().toInstant());
    }

    private Session toSession(SessionEntity e) {
        return new Session(
                new Ids.SessionId(e.getId().toString()),
                new Ids.TenantId(e.getTenantId()),
                e.getPrincipalSubject() == null ? null : new Principal(
                        e.getPrincipalSubject(),
                        new Ids.TenantId(e.getTenantId()),
                        Set.of(), Map.of(), e.getCreatedAt().toInstant()),
                new Ids.ProfileId(e.getProfileId(), e.getProfileVersion()),
                e.getChannel(),
                List.of(), IntentFrame.empty(), Map.of(),
                e.getCreatedAt().toInstant(),
                e.getEndedAt() == null ? null : e.getEndedAt().toInstant());
    }

    private SessionSummary toSummary(SessionEntity e) {
        long turnCount = turns.findBySessionIdOrderByIdxAsc(e.getId()).size();
        return new SessionSummary(
                new Ids.SessionId(e.getId().toString()),
                new Ids.TenantId(e.getTenantId()),
                new Ids.ProfileId(e.getProfileId(), e.getProfileVersion()),
                e.getPrincipalSubject(),
                e.getChannel(),
                (int) turnCount,
                e.getCreatedAt().toInstant(),
                e.getEndedAt() == null ? null : e.getEndedAt().toInstant());
    }

    // ---- JSON helpers ---------------------------------------------------

    private String writeContentJson(String text, Map<String, Object> metadata) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("text", text == null ? "" : text);
        envelope.put("metadata", metadata == null ? Map.of() : metadata);
        return writeJson(envelope);
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode JSON for turn column", ex);
        }
    }

    private <T> T readJson(String raw, TypeReference<T> type) {
        if (raw == null || raw.isBlank()) return json.convertValue(List.of(), type);
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to decode JSON for turn column", ex);
        }
    }

    private Map<String, Object> readMap(Object source) {
        if (source == null) return new LinkedHashMap<>();
        if (source instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (source instanceof String s) {
            if (s.isBlank()) return new LinkedHashMap<>();
            try {
                return new LinkedHashMap<>(json.readValue(s, STRING_MAP));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("failed to decode map", ex);
            }
        }
        return new LinkedHashMap<>(json.convertValue(source, STRING_MAP));
    }
}
