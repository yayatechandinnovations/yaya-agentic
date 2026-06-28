package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.sms.SmsOnboardingState;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSender;

import java.time.Instant;
import java.util.Map;

/**
 * Wire DTOs for {@code /v1/admin/tenants/{tenantId}/sms/senders}. Like the
 * compliance DTOs, these never surface the Twilio sender SID or any
 * carrier-internal identifier ({@code sms-channel-design.md} §2) — the tenant
 * sees status + a single user-facing onboarding message. The webhook secret is
 * never surfaced either.
 */
public final class SmsSenderDtos {

    private SmsSenderDtos() {}

    /** Body for POST. {@code campaignId} is required for US, forbidden for DR;
     *  {@code senderKind}/{@code acquisition} default to LONG_CODE/PURCHASED. */
    public record SenderRequest(
            String phoneE164,
            String market,            // "US" | "DO"
            String senderKind,        // optional; defaults LONG_CODE
            String acquisition,       // optional; defaults PURCHASED
            String campaignId,        // US only
            String defaultLocale
    ) {}

    /** Body for attaching a DR regulatory bundle. */
    public record RegulatoryBundleRequest(String regulatoryBundleSid) {}

    public record SenderResponse(
            String              id,
            String              tenantId,
            String              phoneE164,
            String              market,
            String              senderKind,
            String              acquisition,
            String              campaignId,
            boolean             hasRegulatoryBundle,
            String              status,
            OnboardingView      onboarding,
            String              defaultLocale,
            Map<String, Object> metadata,
            Instant             createdAt,
            Instant             updatedAt,
            Instant             lastSyncAt
    ) {
        public static SenderResponse of(SmsSender s) {
            return new SenderResponse(
                    s.id().value(),
                    s.tenant().value(),
                    s.phoneE164(),
                    s.market().name(),
                    s.senderKind().name(),
                    s.acquisition().name(),
                    s.campaignId() == null ? null : s.campaignId().value(),
                    s.regulatoryBundleSid() != null,
                    s.status().name(),
                    OnboardingView.of(s.onboarding()),
                    s.defaultLocale(),
                    s.metadata(),
                    s.createdAt(),
                    s.updatedAt(),
                    s.lastSyncAt());
        }
    }

    /** Onboarding view — drops the carrier SID + raw failure code, keeps a
     *  single user-facing status + message. */
    public record OnboardingView(String status, String failureMessage, Instant lastPolledAt, int pollAttempts) {
        public static OnboardingView of(SmsOnboardingState o) {
            return new OnboardingView(o.lastStatus(), o.lastFailureMessage(), o.lastPolledAt(), o.pollAttempts());
        }
    }
}
