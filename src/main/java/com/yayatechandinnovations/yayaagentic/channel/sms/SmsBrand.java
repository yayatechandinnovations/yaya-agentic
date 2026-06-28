package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;

import java.time.Instant;

/**
 * The tenant's registered business identity with the carriers (A2P 10DLC
 * Brand). One per tenant in v1 — service-enforced; the table admits N so the
 * multi-brand seam ({@code sms-channel-design.md} §4.2) needs no schema change.
 *
 * <p>Registered as a Twilio TrustHub <em>Secondary Customer Profile</em> under
 * the platform's one Primary Profile (§6.4) — the ISV shape that keeps Twilio
 * and TCR fully invisible to the tenant (§2). The tenant supplies plain
 * {@link BusinessIdentity}; the platform renders the rest.
 *
 * @param twilioProfileSid BU... — populated after submit
 * @param tcrBrandId       assigned by TCR via Twilio after verification
 */
public record SmsBrand(
        SmsBrandId       id,
        TenantId         tenant,
        BusinessIdentity business,
        BrandStatus      status,
        String           twilioProfileSid,
        String           tcrBrandId,
        String           lastFailureCode,
        String           lastFailureMessage,
        Instant          createdAt,
        Instant          updatedAt,
        Instant          lastSyncAt
) {

    public SmsBrand {
        if (id == null)       throw new IllegalArgumentException("id required");
        if (tenant == null)   throw new IllegalArgumentException("tenant required");
        if (business == null) throw new IllegalArgumentException("business required");
        if (status == null)   throw new IllegalArgumentException("status required");
    }
}
