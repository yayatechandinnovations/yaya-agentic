package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.profile.Capability;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory catalog for capabilities + tool descriptors. The full
 * registry SPIs land in M1 alongside the admin REST surface; this is the
 * narrowest thing the M0 engine needs to look up by ID.
 */
@Component
public class M0Catalog {

    private final Map<Ids.CapabilityId, Capability> capabilities = new ConcurrentHashMap<>();
    private final Map<Ids.ToolId, ToolDescriptor> tools = new ConcurrentHashMap<>();

    public void registerCapability(Capability c) {
        capabilities.put(c.id(), c);
    }

    public void registerTool(ToolDescriptor t) {
        tools.put(t.id(), t);
    }

    public Optional<Capability> capability(Ids.CapabilityId id) {
        return Optional.ofNullable(capabilities.get(id));
    }

    public Optional<ToolDescriptor> tool(Ids.ToolId id) {
        return Optional.ofNullable(tools.get(id));
    }
}
