package com.yayatechandinnovations.yayaagentic.channel.sms;

import java.util.Set;

/**
 * Lifecycle state of an {@link SmsBrand} — the carrier-side registration of a
 * tenant's business identity. Transition graph from {@code sms-channel-design.md}
 * §6.2:
 *
 * <pre>
 *   DRAFT -> SUBMITTING -> PENDING_VERIFICATION -> VERIFIED
 *                  |                |
 *                  +----------------+--> FAILED  (edit -> DRAFT)
 * </pre>
 *
 * <p>A {@link SmsCampaign} cannot leave {@code DRAFT} until its Brand reaches
 * {@link #VERIFIED} — enforced in the service layer, never trusted from the
 * client.
 */
public enum BrandStatus {

    DRAFT,
    SUBMITTING,
    PENDING_VERIFICATION,
    VERIFIED,
    FAILED;

    public Set<BrandStatus> legalTransitions() {
        return switch (this) {
            case DRAFT                -> Set.of(SUBMITTING);
            case SUBMITTING           -> Set.of(PENDING_VERIFICATION, FAILED);
            case PENDING_VERIFICATION -> Set.of(VERIFIED, FAILED);
            case VERIFIED             -> Set.of();              // terminal-happy
            case FAILED               -> Set.of(DRAFT);        // edit -> returns to DRAFT
        };
    }

    public boolean canTransitionTo(BrandStatus next) {
        return legalTransitions().contains(next);
    }

    /** DRAFT + FAILED are operator-editable; everything else is immutable. */
    public boolean isEditable() {
        return this == DRAFT || this == FAILED;
    }

    /** Only a VERIFIED brand may host a campaign submission. */
    public boolean canHostCampaignSubmission() {
        return this == VERIFIED;
    }
}
