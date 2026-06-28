package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * The tenant's legal business identity, as registered with the carriers for
 * A2P 10DLC (the TCR Brand). Tenant PII at the same tier as the WhatsApp
 * {@code BusinessProfile} — same redaction and at-rest posture
 * ({@code sms-channel-design.md} §11).
 *
 * <p>{@code taxId} is the US EIN; a mismatch against IRS records is the single
 * biggest cause of Brand verification rejection, so the UI collects it
 * carefully and the service surfaces the failure verbatim.
 */
public record BusinessIdentity(
        String legalName,
        String taxId,            // US EIN
        String address,
        String vertical,         // carrier-enumerated business vertical
        String websiteUrl,
        String contactEmail
) {

    public BusinessIdentity {
        if (legalName == null || legalName.isBlank()) {
            throw new IllegalArgumentException("legalName must not be blank");
        }
        if (taxId == null || taxId.isBlank()) {
            throw new IllegalArgumentException("taxId (EIN) must not be blank");
        }
    }
}
