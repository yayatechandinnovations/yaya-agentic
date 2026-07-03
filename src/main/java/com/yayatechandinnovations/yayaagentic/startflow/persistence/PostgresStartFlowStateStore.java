package com.yayatechandinnovations.yayaagentic.startflow.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.SessionStartStateEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SessionStartStateRepository;
import com.yayatechandinnovations.yayaagentic.startflow.StartFlowCursor;
import com.yayatechandinnovations.yayaagentic.startflow.StartFlowStateStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * JPA-backed {@link StartFlowStateStore} against {@code session_start_state}.
 * See {@code docs/design/session-start-flows.md} §7.3.
 */
@Component
@ConditionalOnMissingBean(value = StartFlowStateStore.class, ignored = PostgresStartFlowStateStore.class)
public class PostgresStartFlowStateStore implements StartFlowStateStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final SessionStartStateRepository repo;
    private final ObjectMapper json;

    public PostgresStartFlowStateStore(SessionStartStateRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StartFlowCursor> load(Ids.SessionId session) {
        if (session == null) return Optional.empty();
        return repo.findById(session.value()).map(e -> new StartFlowCursor(
                new Ids.SessionId(e.getSessionId()),
                new Ids.StartFlowId(e.getFlowId(), e.getFlowVersion()),
                e.getCurrentStepKey(),
                readMap(e.getScratchJson()),
                readMap(e.getStepOutputsJson()),
                e.getUpdatedAt() == null ? null : e.getUpdatedAt().toInstant()));
    }

    @Override
    @Transactional
    public void save(StartFlowCursor cursor) {
        if (cursor == null) return;
        SessionStartStateEntity e = repo.findById(cursor.session().value())
                .orElseGet(() -> new SessionStartStateEntity(
                        cursor.session().value(), cursor.flow().value(),
                        cursor.flow().version(), cursor.currentStepKey()));
        e.setFlowId(cursor.flow().value());
        e.setFlowVersion(cursor.flow().version());
        e.setCurrentStepKey(cursor.currentStepKey());
        e.setScratchJson(writeMap(cursor.scratch()));
        e.setStepOutputsJson(writeMap(cursor.stepOutputs()));
        e.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        repo.save(e);
    }

    @Override
    @Transactional
    public void delete(Ids.SessionId session) {
        if (session != null) repo.deleteById(session.value());
    }

    private String writeMap(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode start-flow cursor json", ex);
        }
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return json.readValue(raw, MAP);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to decode start-flow cursor json", ex);
        }
    }
}
