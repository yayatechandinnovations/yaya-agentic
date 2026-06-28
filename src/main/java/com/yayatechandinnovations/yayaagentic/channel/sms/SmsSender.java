package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;

import java.time.Instant;
import java.util.Map;

/**
 * A registered SMS phone-number endpoint — the conversational-channel sender,
 * sibling to {@code channel.whatsapp.WhatsAppSender}. Owned by exactly one
 * tenant; routes to profiles via the shared {@code channel_bindings}
 * ({@code endpoint_kind = 'SMS_SENDER'}).
 *
 * <p>Compliance path is market-dependent ({@code sms-channel-design.md} §7): a
 * {@link SmsMarket#US} sender carries a {@code campaignId} (and goes ACTIVE
 * only once that campaign is APPROVED, §5.2); a {@link SmsMarket#DO} sender
 * carries a {@code regulatoryBundleSid} instead. The two are mutually exclusive.
 *
 * @param campaignId          A2P campaign this sender sends under (US); null otherwise
 * @param regulatoryBundleSid Twilio regulatory bundle (DR); null otherwise
 * @param webhookSecret       server-minted; the inbound signature trust root — never surfaced on the wire
 */
public record SmsSender(
        SmsSenderId         id,
        TenantId            tenant,
        String              phoneE164,
        SmsMarket           market,
        SenderKind          senderKind,
        Acquisition         acquisition,
        SmsCampaignId       campaignId,
        String              regulatoryBundleSid,
        SmsSenderStatus     status,
        SmsOnboardingState  onboarding,
        String              webhookSecret,
        String              defaultLocale,
        Map<String, Object> metadata,
        Instant             createdAt,
        Instant             updatedAt,
        Instant             lastSyncAt
) {

    public SmsSender {
        if (id == null)      throw new IllegalArgumentException("id required");
        if (tenant == null)  throw new IllegalArgumentException("tenant required");
        if (market == null)  throw new IllegalArgumentException("market required");
        if (status == null)  throw new IllegalArgumentException("status required");
        if (phoneE164 == null || phoneE164.isBlank()) {
            throw new IllegalArgumentException("phoneE164 must not be blank");
        }
        if (campaignId != null && regulatoryBundleSid != null) {
            throw new IllegalArgumentException(
                    "a sender is either campaign-backed (US) or bundle-backed (DR), never both");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
