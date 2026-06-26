package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

/**
 * Meta-required business profile attached to every WhatsApp sender. Twilio's
 * Senders API and Meta's Business Manager both consume these fields — they're
 * what Meta evaluates during the business verification step that gates the
 * {@code PENDING_REVIEW} → {@code ACTIVE} transition.
 *
 * <p>The tenant-facing wizard (§15.1 step 1) collects all of this in step 1.
 * The platform never modifies these on the operator's behalf.
 *
 * @param vertical Meta-enumerated vertical code (e.g. {@code RETAIL},
 *                 {@code FINANCIAL_SERVICES}). Validation against the Meta
 *                 catalog happens at write time in the service layer.
 */
public record BusinessProfile(
        String legalName,
        String displayName,
        String contactEmail,
        String address,
        String websiteUrl,
        String vertical,
        String about
) {

    public BusinessProfile {
        if (legalName == null || legalName.isBlank()) {
            throw new IllegalArgumentException("legalName must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (contactEmail == null || contactEmail.isBlank()) {
            throw new IllegalArgumentException("contactEmail must not be blank");
        }
        if (vertical == null || vertical.isBlank()) {
            throw new IllegalArgumentException("vertical must not be blank");
        }
    }
}
