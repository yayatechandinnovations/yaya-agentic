package com.yayatechandinnovations.yayaagentic.core.entity;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase-1 in-memory implementation. Per-session insertion-ordered map so
 * {@link #all} returns a stable order matching capture order — relied on
 * by prompt projection (newest at the bottom, alongside the verbatim tail).
 *
 * <p>Replaced in phase 2 by a Postgres-backed implementation that rehydrates
 * from the recorder on session resume. Until then, restart loses memory —
 * acceptable for the phase 1 acceptance criteria where rehydration happens
 * from {@code Turn.ToolResult.capturedEntities} on load.</p>
 */
@Component
@ConditionalOnMissingBean(EntityMemory.class)
public class InMemoryEntityMemory implements EntityMemory {

    private final Map<Ids.SessionId, Map<String, EntityRef>> bySession = new ConcurrentHashMap<>();

    @Override
    public void capture(Ids.SessionId session, EntityRef entity) {
        if (session == null || entity == null) return;
        Map<String, EntityRef> bag = bySession.computeIfAbsent(session,
                k -> Collections.synchronizedMap(new LinkedHashMap<>()));
        synchronized (bag) {
            if (entity.kind() == EntityKind.COLLECTION) {
                EntityRef existing = bag.get(entity.name());
                List<Object> merged = new ArrayList<>();
                if (existing != null && existing.value() instanceof List<?> lst) {
                    merged.addAll(lst);
                }
                merged.add(entity.value());
                bag.put(entity.name(), new EntityRef(
                        entity.name(),
                        List.copyOf(merged),
                        entity.scope(),
                        EntityKind.COLLECTION,
                        entity.visibleToLlm(),
                        entity.producedBy(),
                        entity.capturedAt(),
                        entity.metadata()));
            } else {
                bag.put(entity.name(), entity);
            }
        }
    }

    @Override
    public Optional<EntityRef> resolve(Ids.SessionId session, String name) {
        if (session == null || name == null) return Optional.empty();
        Map<String, EntityRef> bag = bySession.get(session);
        if (bag == null) return Optional.empty();
        synchronized (bag) {
            return Optional.ofNullable(bag.get(name));
        }
    }

    @Override
    public List<EntityRef> all(Ids.SessionId session) {
        if (session == null) return List.of();
        Map<String, EntityRef> bag = bySession.get(session);
        if (bag == null) return List.of();
        synchronized (bag) {
            return List.copyOf(bag.values());
        }
    }

    @Override
    public void clear(Ids.SessionId session, String name) {
        if (session == null || name == null) return;
        Map<String, EntityRef> bag = bySession.get(session);
        if (bag == null) return;
        synchronized (bag) {
            bag.remove(name);
        }
    }

    @Override
    public void clearScope(Ids.SessionId session, EntityScope scope) {
        if (session == null || scope == null) return;
        if (scope == EntityScope.SESSION) {
            bySession.remove(session);
            return;
        }
        // INTENT / TURN scopes — phase 2. Implement as a filter so the
        // engine wiring can already invoke clearScope at the right boundary
        // without behavior change in phase 1.
        Map<String, EntityRef> bag = bySession.get(session);
        if (bag == null) return;
        synchronized (bag) {
            bag.values().removeIf(ref -> ref.scope() == scope);
        }
    }
}
