package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import java.util.Set;

/**
 * Lifecycle state of a {@link WhatsAppSender}. The transition graph is
 * spelled out in {@code whatsapp-channel-design.md} §6 (state machine) — this
 * enum encodes the legal transitions so the service layer can validate them
 * cheaply without pattern-matching on strings.
 *
 * <p>Per-state I/O posture (§6.1):
 * <table>
 *   <tr><th>State</th><th>Inbound</th><th>Outbound</th><th>Bindings active</th></tr>
 *   <tr><td>DRAFT</td><td>no</td><td>no</td><td>no</td></tr>
 *   <tr><td>SUBMITTING</td><td>no</td><td>no</td><td>no</td></tr>
 *   <tr><td>PENDING_REVIEW</td><td>yes (refused friendly)</td><td>no</td><td>yes (dispatch refused)</td></tr>
 *   <tr><td>ACTIVE</td><td>yes</td><td>yes</td><td>yes</td></tr>
 *   <tr><td>SUSPENDED</td><td>yes (acked, no LLM)</td><td>no</td><td>paused</td></tr>
 *   <tr><td>FAILED</td><td>no</td><td>no</td><td>no</td></tr>
 *   <tr><td>REVOKED</td><td>no</td><td>no</td><td>no</td></tr>
 * </table>
 */
public enum SenderStatus {

    DRAFT,
    SUBMITTING,
    PENDING_REVIEW,
    ACTIVE,
    SUSPENDED,
    FAILED,
    REVOKED;

    /** Legal next-state set from this state, per §6's state machine. */
    public Set<SenderStatus> legalTransitions() {
        return switch (this) {
            case DRAFT          -> Set.of(SUBMITTING, REVOKED);
            case SUBMITTING     -> Set.of(PENDING_REVIEW, FAILED);
            case PENDING_REVIEW -> Set.of(ACTIVE, FAILED);
            case ACTIVE         -> Set.of(SUSPENDED, REVOKED);
            case SUSPENDED      -> Set.of(ACTIVE, REVOKED);
            case FAILED         -> Set.of(DRAFT, REVOKED);    // edit → returns to DRAFT
            case REVOKED        -> Set.of();                  // terminal
        };
    }

    public boolean canTransitionTo(SenderStatus next) {
        return legalTransitions().contains(next);
    }

    /** DRAFT + FAILED are editable; everything else (incl. ACTIVE) is immutable. */
    public boolean isEditable() {
        return this == DRAFT || this == FAILED;
    }

    public boolean isTerminal() {
        return this == REVOKED;
    }

    /** True when the sender accepts outbound dispatch. Only ACTIVE qualifies. */
    public boolean acceptsOutbound() {
        return this == ACTIVE;
    }

    /** True when the sender accepts inbound (even if it just logs+drops). */
    public boolean acceptsInbound() {
        return this == ACTIVE || this == PENDING_REVIEW || this == SUSPENDED;
    }
}
