package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * Platform-stable identifier for an {@link SmsCampaign} — a registered use case
 * under an {@link SmsBrand}. A tenant has one or more. Channel-scoped; see
 * {@code sms-channel-design.md} §6.
 */
public record SmsCampaignId(String value) {

    public SmsCampaignId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SmsCampaignId value must not be blank");
        }
    }
}
