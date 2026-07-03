package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;
import java.util.List;

/**
 * A tenant-scoped, versioned, ordered list of {@link StartStep}s run at session
 * start — before the LLM. Referenced by a WhatsApp sender / chat entry point
 * (or a profile default). See {@code docs/design/session-start-flows.md} §5.1, §6.
 */
public record StartFlow(
        Ids.StartFlowId id,
        Ids.TenantId tenant,
        String name,
        String description,
        List<StartStep> steps,
        GreetingPolicy greeting,
        FlowPolicy policy,
        FlowStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public enum FlowStatus { DRAFT, ACTIVE, ARCHIVED }

    public StartFlow {
        if (id == null) throw new IllegalArgumentException("StartFlow.id must be set");
        if (tenant == null) throw new IllegalArgumentException("StartFlow.tenant must be set");
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (greeting == null) greeting = GreetingPolicy.staticFallback(null);
        if (policy == null) policy = FlowPolicy.defaults();
        if (status == null) status = FlowStatus.ACTIVE;
    }

    public int version() { return id.version(); }
}
