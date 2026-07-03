package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.BusinessProfile;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.OnboardingState;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;

import java.time.Instant;
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

    /** Mirrors {@link BusinessProfile} but as a wire DTO (Jackson-friendly). */
    public record BusinessProfileRequest(
            String legalName,
            String displayName,
            String contactEmail,
            String address,
            String websiteUrl,
            String vertical,
            String about
    ) {
        public BusinessProfile toDomain() {
            return new BusinessProfile(legalName, displayName, contactEmail,
                    address, websiteUrl, vertical, about);
        }
    }

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
            String                 startFlowId
    ) {

        public static SenderResponse of(WhatsAppSender s) {
            BusinessProfile b = s.business();
            BusinessProfileRequest bp = new BusinessProfileRequest(
                    b.legalName(), b.displayName(), b.contactEmail(), b.address(),
                    b.websiteUrl(), b.vertical(), b.about());

            return new SenderResponse(
                    s.id().value(),
                    s.tenant().value(),
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
                    s.startFlowId()
            );
        }
    }

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
}
