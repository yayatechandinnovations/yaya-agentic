package com.yayatechandinnovations.yayaagentic.startflow.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.StartFlowEntity;
import com.yayatechandinnovations.yayaagentic.persistence.StartFlowRepository;
import com.yayatechandinnovations.yayaagentic.startflow.FlowPolicy;
import com.yayatechandinnovations.yayaagentic.startflow.GreetingPolicy;
import com.yayatechandinnovations.yayaagentic.startflow.StartFlow;
import com.yayatechandinnovations.yayaagentic.startflow.StartFlowRegistry;
import com.yayatechandinnovations.yayaagentic.startflow.StartStep;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JPA-backed {@link StartFlowRegistry} against {@code session_start_flows}.
 * {@link #find} returns the exact pinned version; {@link #findLatest} returns
 * the highest-version non-archived row for an id. Mirrors
 * {@code PostgresProfileRegistry}. See {@code docs/design/session-start-flows.md} §6, §10.
 */
@Component
@ConditionalOnMissingBean(value = StartFlowRegistry.class, ignored = PostgresStartFlowRegistry.class)
public class PostgresStartFlowRegistry implements StartFlowRegistry {

    private static final TypeReference<List<StartStep>> STEP_LIST = new TypeReference<>() {};

    private final StartFlowRepository repo;
    private final ObjectMapper json;

    public PostgresStartFlowRegistry(StartFlowRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StartFlow> find(Ids.TenantId tenant, Ids.StartFlowId id) {
        return repo.findById(new StartFlowEntity.PK(tenant.value(), id.value(), id.version()))
                .map(this::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StartFlow> findLatest(Ids.TenantId tenant, Ids.StartFlowId id) {
        return repo.findByTenantIdAndIdOrderByVersionDesc(tenant.value(), id.value()).stream()
                .filter(e -> !"ARCHIVED".equals(e.getStatus()))
                .findFirst()
                .map(this::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StartFlow> listFor(Ids.TenantId tenant) {
        return repo.findByTenantId(tenant.value()).stream().map(this::toRecord).toList();
    }

    @Override
    @Transactional
    public void register(StartFlow flow) {
        StartFlowEntity.PK pk = new StartFlowEntity.PK(
                flow.tenant().value(), flow.id().value(), flow.id().version());
        StartFlowEntity e = repo.findById(pk).orElseGet(() -> new StartFlowEntity(
                flow.tenant().value(), flow.id().value(), flow.id().version(), flow.name()));
        e.setName(flow.name());
        e.setDescription(flow.description());
        // Write the steps through the List<StartStep> type so Jackson knows the
        // element base type and emits the "kind" discriminator for each sealed
        // step (a bare writeValueAsString erases it and the read-back fails).
        e.setStepsJson(writeSteps(flow.steps()));
        e.setGreetingJson(writeJson(flow.greeting()));
        e.setPolicyJson(writeJson(flow.policy()));
        e.setStatus(flow.status().name());
        repo.save(e);
    }

    private StartFlow toRecord(StartFlowEntity e) {
        List<StartStep> steps = readJson(e.getStepsJson(), STEP_LIST);
        GreetingPolicy greeting = readValue(e.getGreetingJson(), GreetingPolicy.class);
        FlowPolicy policy = readValue(e.getPolicyJson(), FlowPolicy.class);
        return new StartFlow(
                new Ids.StartFlowId(e.getId(), e.getVersion()),
                new Ids.TenantId(e.getTenantId()),
                e.getName(),
                e.getDescription(),
                steps,
                greeting,
                policy,
                StartFlow.FlowStatus.valueOf(e.getStatus()),
                toInstant(e.getCreatedAt()),
                toInstant(e.getUpdatedAt()));
    }

    private static Instant toInstant(OffsetDateTime t) { return t == null ? null : t.toInstant(); }

    private String writeSteps(List<StartStep> steps) {
        try {
            return json.writerFor(STEP_LIST).writeValueAsString(steps);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode start-flow steps", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode JSON for start-flow column", ex);
        }
    }

    private <T> T readJson(String raw, TypeReference<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to decode JSON from start-flow column", ex);
        }
    }

    private <T> T readValue(String raw, Class<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to decode JSON from start-flow column", ex);
        }
    }
}
