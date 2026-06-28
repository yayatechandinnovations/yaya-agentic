package com.yayatechandinnovations.yayaagentic.channel.sms;

import java.util.Set;

/**
 * Lifecycle state of an {@link SmsCampaign} — the carrier-side registration of
 * a tenant use case. Transition graph from {@code sms-channel-design.md} §6.2:
 *
 * <pre>
 *   DRAFT -> SUBMITTING -> PENDING_REVIEW -> APPROVED
 *                  |             |
 *                  +-------------+--> FAILED  (edit -> DRAFT)
 *   APPROVED -> SUSPENDED  (carrier suspends; operator remediates)
 * </pre>
 *
 * <p>An SMS sender cannot reach {@code ACTIVE} until its campaign is
 * {@link #APPROVED} (§2 trust principle; §5.2 added gate) — compliance is a
 * hard gate, not advice.
 */
public enum CampaignStatus {

    DRAFT,
    SUBMITTING,
    PENDING_REVIEW,
    APPROVED,
    SUSPENDED,
    FAILED;

    public Set<CampaignStatus> legalTransitions() {
        return switch (this) {
            case DRAFT          -> Set.of(SUBMITTING);
            case SUBMITTING     -> Set.of(PENDING_REVIEW, FAILED);
            case PENDING_REVIEW -> Set.of(APPROVED, FAILED);
            case APPROVED       -> Set.of(SUSPENDED);
            case SUSPENDED      -> Set.of(APPROVED, FAILED);   // resume or give up
            case FAILED         -> Set.of(DRAFT);              // edit -> returns to DRAFT
        };
    }

    public boolean canTransitionTo(CampaignStatus next) {
        return legalTransitions().contains(next);
    }

    public boolean isEditable() {
        return this == DRAFT || this == FAILED;
    }

    /** Only an APPROVED campaign lets its attached senders go ACTIVE / send. */
    public boolean permitsSending() {
        return this == APPROVED;
    }
}
