package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.system.AddressingPolicy;
import com.yayatechandinnovations.yayaagentic.channel.system.CapabilityMask;
import com.yayatechandinnovations.yayaagentic.channel.system.MediationMode;
import com.yayatechandinnovations.yayaagentic.channel.system.RateAndQuiet;
import com.yayatechandinnovations.yayaagentic.channel.system.SenderTrust;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;

import java.time.Instant;

/**
 * Admin DTOs for the "system inbound" sender block ({@code
 * system-messaging-design.md} §3, §14). The Flutter admin binds field errors to
 * the stable codes {@code SystemSenderService} raises.
 */
public final class SystemSenderDtos {

    private SystemSenderDtos() {}

    /** Create/update body. {@code trust} and {@code addressing} are the domain
     *  records verbatim; missing values default in the service. */
    public record SenderRequest(
            String entryPoint,
            String displayName,
            boolean enabled,
            SenderTrust trust,
            String mediation,
            AddressingPolicy addressing,
            String responseToolId,
            CapabilityMask capabilityMask,
            RateAndQuiet limits
    ) {
        public MediationMode mediationMode() {
            if (mediation == null || mediation.isBlank()) return MediationMode.VERBATIM;
            return MediationMode.valueOf(mediation.trim().toUpperCase());
        }
    }

    public record SenderResponse(
            String id,
            String tenantId,
            String entryPoint,
            String displayName,
            boolean enabled,
            SenderTrust trust,
            String mediation,
            AddressingPolicy addressing,
            String responseToolId,
            CapabilityMask capabilityMask,
            RateAndQuiet limits,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static SenderResponse of(SystemSender s) {
            return new SenderResponse(
                    s.id().value(),
                    s.tenant().value(),
                    s.entryPoint(),
                    s.displayName(),
                    s.enabled(),
                    s.trust(),
                    s.mediation().name(),
                    s.addressing(),
                    s.responseTool().map(t -> t.value()).orElse(null),
                    s.capabilityMask().orElse(null),
                    s.limits().orElse(null),
                    s.createdAt(),
                    s.updatedAt());
        }
    }
}
