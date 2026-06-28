package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A registered use case under an {@link SmsBrand}. A tenant has one or more.
 * Carriers approve per-campaign; an SMS sender attaches to exactly one and
 * inherits its sending eligibility (§5.2, §6.1).
 *
 * @param sampleMessages       2..5 strings that MUST match real traffic (§6.5)
 * @param evidence             licensing/jurisdiction/age-gate evidence for
 *                             restricted use cases (§6.3); null otherwise
 * @param requiresOperatorReview true gates the submit behind platform-operator
 *                             release; defaults true for restricted use cases
 * @param messagingServiceSid  the Twilio Messaging Service senders attach to
 */
public record SmsCampaign(
        SmsCampaignId    id,
        TenantId         tenant,
        SmsBrandId       brand,
        A2pUseCase       useCase,
        String           description,
        List<String>     sampleMessages,
        OptInDescription optIn,
        Map<String, Object> evidence,
        boolean          requiresOperatorReview,
        CampaignStatus   status,
        String           twilioCampaignSid,
        String           messagingServiceSid,
        String           lastFailureCode,
        String           lastFailureMessage,
        Instant          createdAt,
        Instant          updatedAt,
        Instant          lastSyncAt
) {

    public SmsCampaign {
        if (id == null)      throw new IllegalArgumentException("id required");
        if (tenant == null)  throw new IllegalArgumentException("tenant required");
        if (brand == null)   throw new IllegalArgumentException("brand required");
        if (useCase == null) throw new IllegalArgumentException("useCase required");
        if (status == null)  throw new IllegalArgumentException("status required");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (sampleMessages == null || sampleMessages.size() < 2 || sampleMessages.size() > 5) {
            throw new IllegalArgumentException("sampleMessages must contain 2..5 entries");
        }
        if (optIn == null) throw new IllegalArgumentException("optIn required");
        sampleMessages = List.copyOf(sampleMessages);
        evidence = evidence == null ? null : Map.copyOf(evidence);
    }
}
