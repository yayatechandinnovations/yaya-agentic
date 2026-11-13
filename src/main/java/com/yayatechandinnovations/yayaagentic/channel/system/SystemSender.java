package com.yayatechandinnovations.yayaagentic.channel.system;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;
import java.util.Optional;

/**
 * A registered integration sender — the operator-configured "system inbound"
 * block on a chat entry point (§3). Switches the feature on and shapes trust,
 * mediation, the response tool, and addressing for one linked application.
 *
 * <p>SM-0 wires {@code enabled}, {@code trust}, {@code mediation}, and
 * {@code addressing}. {@code responseTool} (SM-2), and the capability-mask /
 * media / limits policies live on the entity as nullable JSON until their
 * milestones land.
 *
 * @param entryPoint the chat entry point this sender delivers into — "the chat
 *                   the user added" (§6)
 */
public record SystemSender(
        Ids.SystemSenderId id,
        Ids.TenantId tenant,
        String entryPoint,
        String displayName,
        boolean enabled,
        SenderTrust trust,
        MediationMode mediation,
        Optional<Ids.ToolId> responseTool,
        AddressingPolicy addressing,
        Optional<CapabilityMask> capabilityMask,
        Optional<RateAndQuiet> limits,
        Instant createdAt,
        Instant updatedAt,
        /** The WhatsApp number this sender also delivers through (§6.2 of
         *  whatsapp-ops-integration-design.md); empty = chat only. */
        Optional<String> whatsappSenderId
) {
    /** Back-compat: a chat-only sender. */
    public SystemSender(Ids.SystemSenderId id, Ids.TenantId tenant, String entryPoint, String displayName,
                        boolean enabled, SenderTrust trust, MediationMode mediation,
                        Optional<Ids.ToolId> responseTool, AddressingPolicy addressing,
                        Optional<CapabilityMask> capabilityMask, Optional<RateAndQuiet> limits,
                        Instant createdAt, Instant updatedAt) {
        this(id, tenant, entryPoint, displayName, enabled, trust, mediation, responseTool, addressing,
                capabilityMask, limits, createdAt, updatedAt, Optional.empty());
    }

    public SystemSender {
        if (id == null) throw new IllegalArgumentException("id required");
        if (tenant == null) throw new IllegalArgumentException("tenant required");
        if (entryPoint == null || entryPoint.isBlank())
            throw new IllegalArgumentException("entryPoint required");
        if (displayName == null || displayName.isBlank())
            throw new IllegalArgumentException("displayName required");
        trust = trust == null ? SenderTrust.none() : trust;
        mediation = mediation == null ? MediationMode.VERBATIM : mediation;
        responseTool = responseTool == null ? Optional.empty() : responseTool;
        addressing = addressing == null ? AddressingPolicy.principalsOnly() : addressing;
        capabilityMask = capabilityMask == null ? Optional.empty() : capabilityMask;
        limits = limits == null ? Optional.empty() : limits;
        whatsappSenderId = whatsappSenderId == null ? Optional.empty() : whatsappSenderId;
    }

    /** The tool-authority mask that governs the mediation reply turn (§9.1). */
    public CapabilityMask effectiveMask() {
        return CapabilityMask.effective(capabilityMask.orElse(null),
                responseTool.map(Ids.ToolId::value).orElse(null));
    }
}
