package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeSource;
import com.yayatechandinnovations.yayaagentic.profile.Capability;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory catalog for capabilities + tool descriptors + knowledge
 * sources + profile→source bindings. Hydrated from Postgres on startup
 * by {@code RuntimeCatalogLoader}, and kept in sync with admin writes.
 */
@Component
public class M0Catalog {

    private final Map<Ids.CapabilityId, Capability> capabilities = new ConcurrentHashMap<>();
    private final Map<Ids.ToolId, ToolDescriptor> tools = new ConcurrentHashMap<>();
    private final Map<Ids.KnowledgeSourceId, KnowledgeSource> sources = new ConcurrentHashMap<>();
    private final Map<Ids.ProfileId, List<Ids.KnowledgeSourceId>> profileSources = new ConcurrentHashMap<>();

    public void registerCapability(Capability c) {
        capabilities.put(c.id(), c);
    }

    public void registerTool(ToolDescriptor t) {
        tools.put(t.id(), t);
    }

    public void registerKnowledgeSource(KnowledgeSource s) {
        sources.put(s.id(), s);
    }

    public void bindProfileSources(Ids.ProfileId profile, List<Ids.KnowledgeSourceId> sourceIds) {
        profileSources.put(profile, List.copyOf(sourceIds));
    }

    public Optional<Capability> capability(Ids.CapabilityId id) {
        return Optional.ofNullable(capabilities.get(id));
    }

    public Optional<ToolDescriptor> tool(Ids.ToolId id) {
        return Optional.ofNullable(tools.get(id));
    }

    public Optional<KnowledgeSource> knowledgeSource(Ids.KnowledgeSourceId id) {
        return Optional.ofNullable(sources.get(id));
    }

    public List<Ids.KnowledgeSourceId> sourcesForProfile(Ids.ProfileId profile) {
        return profileSources.getOrDefault(profile, List.of());
    }
}
