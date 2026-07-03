package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link StartFlowRegistry} — the production wiring is
 * {@code PostgresStartFlowRegistry}. Kept as a plain (non-bean) class for unit
 * tests and any deployment that supplies flows programmatically.
 */
public class InMemoryStartFlowRegistry implements StartFlowRegistry {

    private record Key(String tenant, String flow, int version) {}

    private final Map<Key, StartFlow> flows = new ConcurrentHashMap<>();

    @Override
    public Optional<StartFlow> find(Ids.TenantId tenant, Ids.StartFlowId id) {
        if (tenant == null || id == null) return Optional.empty();
        return Optional.ofNullable(flows.get(new Key(tenant.value(), id.value(), id.version())));
    }

    @Override
    public Optional<StartFlow> findLatest(Ids.TenantId tenant, Ids.StartFlowId id) {
        if (tenant == null || id == null) return Optional.empty();
        return flows.values().stream()
                .filter(f -> f.tenant().value().equals(tenant.value())
                        && f.id().value().equals(id.value())
                        && f.status() != StartFlow.FlowStatus.ARCHIVED)
                .max(Comparator.comparingInt(StartFlow::version));
    }

    @Override
    public List<StartFlow> listFor(Ids.TenantId tenant) {
        if (tenant == null) return List.of();
        return flows.values().stream()
                .filter(f -> f.tenant().value().equals(tenant.value()))
                .sorted(Comparator.comparing((StartFlow f) -> f.id().value())
                        .thenComparingInt(StartFlow::version))
                .toList();
    }

    @Override
    public void register(StartFlow flow) {
        if (flow == null) return;
        flows.put(new Key(flow.tenant().value(), flow.id().value(), flow.version()), flow);
    }
}
