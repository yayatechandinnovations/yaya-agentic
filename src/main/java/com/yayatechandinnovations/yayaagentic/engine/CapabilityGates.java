package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Aggregates every {@link CapabilityGate} bean into a single collaborator the
 * engine and prompt builder consult. Spring injects an empty list when no gate
 * is registered, so this is a no-op by default.
 */
@Component
public class CapabilityGates {

    private final List<CapabilityGate> gates;

    public CapabilityGates(List<CapabilityGate> gates) {
        this.gates = gates == null ? List.of() : gates;
    }

    /** The union of capability ids hidden for this (tenant, profile) right now. */
    public Set<Ids.CapabilityId> disabled(Ids.TenantId tenant, Ids.ProfileId profile) {
        if (gates.isEmpty() || tenant == null || profile == null) return Set.of();
        Set<Ids.CapabilityId> out = new HashSet<>();
        for (CapabilityGate gate : gates) {
            Set<Ids.CapabilityId> d = gate.disabledCapabilities(tenant, profile);
            if (d != null && !d.isEmpty()) out.addAll(d);
        }
        return out;
    }
}
