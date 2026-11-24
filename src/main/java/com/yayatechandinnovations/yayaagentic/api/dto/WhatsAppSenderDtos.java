package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.BusinessProfile;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.OnboardingState;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderTransferService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire DTOs for {@code /v1/admin/tenants/{tenantId}/whatsapp-senders}. The
 * webhook secret is NEVER surfaced — it's a platform trust root that only
 * the platform's inbound signature verifier needs to see.
 */
public final class WhatsAppSenderDtos {

    private WhatsAppSenderDtos() {}

    /** Body for POST + PUT. {@code defaultLocale} is optional. */
    public record SenderRequest(
            String                 phoneE164,
            String                 displayName,
            BusinessProfileRequest businessProfile,
            String                 defaultLocale,
            String                 startFlowId
    ) {
        /** Back-compat: no Session Start Flow binding. */
        public SenderRequest(String phoneE164, String displayName,
                             BusinessProfileRequest businessProfile, String defaultLocale) {
            this(phoneE164, displayName, businessProfile, defaultLocale, null);
        }
    }

    /** Optional body for POST {@code .../submit}. {@code verificationMethod}
     *  is {@code sms} (default) or {@code voice} — how WhatsApp delivers the
     *  ownership code to a number that needs one. */
    public record SubmitRequest(String verificationMethod) {}

    /** Body for POST {@code .../verify}: the one-time code WhatsApp sent. */
    public record VerifyRequest(String code) {}

    /** Mirrors {@link BusinessProfile} but as a wire DTO (Jackson-friendly). */
    public record BusinessProfileRequest(
            String legalName,
            String displayName,
            String contactEmail,
            String address,
            String websiteUrl,
            String vertical,
            String about,
            String logoUrl
    ) {
        /** Back-compat: no logo. */
        public BusinessProfileRequest(String legalName, String displayName, String contactEmail,
                                      String address, String websiteUrl, String vertical,
                                      String about) {
            this(legalName, displayName, contactEmail, address, websiteUrl, vertical, about, null);
        }

        public BusinessProfile toDomain() {
            return new BusinessProfile(legalName, displayName, contactEmail,
                    address, websiteUrl, vertical, about, logoUrl);
        }
    }

    /** Body for POST {@code .../logos}: a PNG or JPEG, base64 (a data: URL prefix is tolerated). */
    public record LogoUploadRequest(String contentType, String dataBase64) {}

    /** The hosted logo to store as {@code businessProfile.logoUrl}. */
    public record LogoUploadResponse(String logoUrl) {}

    /** GET / POST / PUT response. */
    public record SenderResponse(
            String                 id,
            String                 tenantId,
            String                 phoneE164,
            String                 displayName,
            String                 approvedDisplayName,
            BusinessProfileRequest businessProfile,
            SenderStatus           status,
            OnboardingStateView    onboarding,
            String                 defaultLocale,
            Map<String, Object>    metadata,
            Instant                createdAt,
            Instant                updatedAt,
            Instant                lastSyncAt,
            String                 startFlowId,
            String                 wabaId,
            String                 messagingAccountId,
            /** {@code WORKSPACE} or {@code PLATFORM} (whatsapp-shared-sender-design.md §17.1). */
            String                 owner,
            /** The calling workspace's assignment to a platform sender; null on its own senders. */
            AssignmentSummary      assignment
    ) {

        public static SenderResponse of(WhatsAppSender s) {
            return of(s, null);
        }

        public static SenderResponse of(WhatsAppSender s, AssignmentSummary assignment) {
            BusinessProfile b = s.business();
            BusinessProfileRequest bp = new BusinessProfileRequest(
                    b.legalName(), b.displayName(), b.contactEmail(), b.address(),
                    b.websiteUrl(), b.vertical(), b.about(), b.logoUrl());

            return new SenderResponse(
                    s.id().value(),
                    s.tenant() == null ? null : s.tenant().value(),
                    s.phoneE164(),
                    s.displayName(),
                    s.approvedDisplayName(),
                    bp,
                    s.status(),
                    OnboardingStateView.of(s.onboarding()),
                    s.defaultLocale(),
                    s.metadata(),
                    s.createdAt(),
                    s.updatedAt(),
                    s.lastSyncAt(),
                    s.startFlowId(),
                    s.wabaId(),
                    s.messagingAccountId(),
                    s.isPlatform() ? "PLATFORM" : "WORKSPACE",
                    assignment
            );
        }
    }

    /** A workspace's view of its assignment to a platform sender (§17.1). */
    public record AssignmentSummary(
            String id,
            String label,
            String status,
            int    dailyOutboundCap,
            String nonMemberReply,
            long   templateSends24h,
            long   members
    ) {
        public static AssignmentSummary of(com.yayatechandinnovations.yayaagentic.channel.whatsapp.shared
                                                   .SenderAssignmentService.View v) {
            return new AssignmentSummary(v.id(), v.label(), v.status(), v.dailyOutboundCap(), v.nonMemberReply(),
                    v.templateSends24h(), v.members());
        }
    }

    /** PUT {@code …/whatsapp-senders/{s}/assignment}: the workspace's own reply to non-staff. */
    public record AssignmentUpdate(String nonMemberReply) {}

    /** {@code …/members}: GET response and full-replace PUT body. */
    public record MemberSet(List<String> phones, Integer count) {}

    /** Trimmed view of {@link OnboardingState} for the wire — drops the raw
     *  Twilio failure code in favour of a single user-facing message. */
    public record OnboardingStateView(
            String  twilioSenderSid,
            String  status,
            String  failureMessage,
            Instant lastPolledAt,
            int     pollAttempts
    ) {
        public static OnboardingStateView of(OnboardingState o) {
            return new OnboardingStateView(
                    o.twilioSenderSid(),
                    o.lastTwilioStatus(),
                    o.lastTwilioFailureMessage(),
                    o.lastPolledAt(),
                    o.pollAttempts()
            );
        }
    }

    /** POST {@code .../{id}/transfer} — whatsapp-sender-transfer-design.md §6.1. */
    public record TransferRequest(
            String  destinationTenant,
            String  profileId,
            Integer profileVersion,
            Boolean copyOptOuts,
            /** whatsapp-shared-sender-design.md §5.2 — make the number a platform sender. */
            Boolean destinationPlatform
    ) {
        public TransferRequest(String destinationTenant, String profileId, Integer profileVersion,
                               Boolean copyOptOuts) {
            this(destinationTenant, profileId, profileVersion, copyOptOuts, null);
        }
    }

    public record TransferEffectsView(
            int          optOutsCopied,
            int          openSessionsClosed,
            int          parkedOutboundDropped,
            int          bindingsDeactivated,
            List<String> systemSendersUnlinked,
            List<String> templatesNotHeldByDestination
    ) {}

    /** {@code destination} and {@code webhookRepoint} are null on a dry run. */
    public record TransferResponse(
            String              transferId,
            boolean             dryRun,
            SenderResponse      source,
            SenderResponse      destination,
            String              webhookRepoint,
            TransferEffectsView effects,
            List<String>        warnings
    ) {
        public static TransferResponse of(WhatsAppSenderTransferService.Outcome o, boolean dryRun) {
            var e = o.effects();
            return new TransferResponse(
                    o.transferId(),
                    dryRun,
                    SenderResponse.of(o.source()),
                    o.destination().map(SenderResponse::of).orElse(null),
                    o.webhookRepoint().orElse(null),
                    new TransferEffectsView(e.optOutsCopied(), e.openSessionsClosed(),
                            e.parkedOutboundDropped(), e.bindingsDeactivated(),
                            e.systemSendersUnlinked(), e.templatesNotHeldByDestination()),
                    o.warnings());
        }
    }
}
