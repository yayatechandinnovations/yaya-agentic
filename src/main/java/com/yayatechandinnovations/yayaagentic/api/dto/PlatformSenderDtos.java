package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.BusinessProfile;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.shared.SenderAssignmentService;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Wire DTOs for {@code /v1/admin/platform-senders} (whatsapp-shared-sender-design.md §17.3). */
public final class PlatformSenderDtos {

    private PlatformSenderDtos() {}

    /** POST / and PUT /{s}; {@code phoneE164} is ignored on PUT. */
    public record PlatformSenderRequest(
            String                                      phoneE164,
            String                                      displayName,
            WhatsAppSenderDtos.BusinessProfileRequest   businessProfile,
            String                                      defaultLocale,
            String                                      unroutedReply
    ) {}

    public record PlatformSenderResponse(
            String                                      id,
            String                                      tenantId,
            String                                      owner,
            String                                      phoneE164,
            String                                      displayName,
            String                                      approvedDisplayName,
            WhatsAppSenderDtos.BusinessProfileRequest   businessProfile,
            SenderStatus                                status,
            WhatsAppSenderDtos.OnboardingStateView      onboarding,
            String                                      defaultLocale,
            Map<String, Object>                         metadata,
            Instant                                     createdAt,
            Instant                                     updatedAt,
            Instant                                     lastSyncAt,
            String                                      unroutedReply,
            long                                        assignmentCount
    ) {
        public static PlatformSenderResponse of(WhatsAppSender s, String unroutedReply, long assignmentCount) {
            BusinessProfile b = s.business();
            return new PlatformSenderResponse(
                    s.id().value(), null, "PLATFORM", s.phoneE164(), s.displayName(), s.approvedDisplayName(),
                    new WhatsAppSenderDtos.BusinessProfileRequest(b.legalName(), b.displayName(), b.contactEmail(),
                            b.address(), b.websiteUrl(), b.vertical(), b.about(), b.logoUrl()),
                    s.status(), WhatsAppSenderDtos.OnboardingStateView.of(s.onboarding()), s.defaultLocale(),
                    s.metadata(), s.createdAt(), s.updatedAt(), s.lastSyncAt(), unroutedReply, assignmentCount);
        }
    }

    /** PUT /{s}/assignments/{tenantId}. */
    public record AssignmentRequest(String label, Integer dailyOutboundCap) {}

    public record AssignmentStats(long templateSends24h, long optOuts7d, long members) {}

    public record AssignmentResponse(
            String          id,
            String          senderId,
            String          tenantId,
            String          label,
            String          status,
            int             dailyOutboundCap,
            String          nonMemberReply,
            String          assignedBy,
            Instant         assignedAt,
            Instant         endedAt,
            AssignmentStats stats
    ) {
        public static AssignmentResponse of(SenderAssignmentService.View v) {
            return new AssignmentResponse(v.id(), v.senderId(), v.tenantId(), v.label(), v.status(),
                    v.dailyOutboundCap(), v.nonMemberReply(), v.assignedBy(), v.assignedAt(), v.endedAt(),
                    new AssignmentStats(v.templateSends24h(), v.optOuts7d(), v.members()));
        }
    }

    public record EndResponse(boolean dryRun, SenderAssignmentService.EndEffects effects) {
        public static EndResponse of(SenderAssignmentService.EndOutcome o) {
            return new EndResponse(o.dryRun(), o.effects());
        }
    }

    /** An inbound no workspace took (§12 «Unrouted»); the phone is masked. */
    public record UnroutedMessage(String messageSid, String phoneMasked, Instant receivedAt, String reason,
                                  String body) {
        public static UnroutedMessage of(InboundMessageEntity e) {
            String phone = e.getEndUserPhone();
            String masked = phone == null || phone.length() < 4 ? "****" : "****" + phone.substring(phone.length() - 4);
            return new UnroutedMessage(e.getMessageSid(), masked,
                    e.getReceivedAt() == null ? null : e.getReceivedAt().toInstant(),
                    e.getProcessingError(), e.getBodyRedacted());
        }
    }

    /** POST /{s}/transfer — the platform sender moves into one workspace. */
    public record TransferToWorkspaceRequest(String destinationTenant, String profileId, Integer profileVersion,
                                             Boolean copyOptOuts) {}

    public record UnroutedList(List<UnroutedMessage> messages) {}
}
