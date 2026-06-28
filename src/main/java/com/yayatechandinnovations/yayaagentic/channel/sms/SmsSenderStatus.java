package com.yayatechandinnovations.yayaagentic.channel.sms;

import java.util.Set;

/**
 * Lifecycle state of an {@link SmsSender}. Deliberately the SAME 7-state
 * machine as {@code channel.whatsapp.SenderStatus} ({@code sms-channel-design.md}
 * §5.2) — the one difference is the precondition on {@code PENDING_REVIEW ->
 * ACTIVE}: a US sender's campaign must be {@code APPROVED} (and a DR sender's
 * regulatory bundle approved). That guard lives in the sender service, not in
 * the transition table.
 *
 * <p>Kept as its own enum rather than importing the WhatsApp one — the two are
 * identical today but coupling SMS to the {@code whatsapp} package would be a
 * smell; a shared {@code channel.SenderStatus} is the eventual unification seam
 * (alongside the inbound/outbound generalization the design calls for).
 */
public enum SmsSenderStatus {

    DRAFT,
    SUBMITTING,
    PENDING_REVIEW,
    ACTIVE,
    SUSPENDED,
    FAILED,
    REVOKED;

    public Set<SmsSenderStatus> legalTransitions() {
        return switch (this) {
            case DRAFT          -> Set.of(SUBMITTING, REVOKED);
            case SUBMITTING     -> Set.of(PENDING_REVIEW, FAILED);
            case PENDING_REVIEW -> Set.of(ACTIVE, FAILED);
            case ACTIVE         -> Set.of(SUSPENDED, REVOKED);
            case SUSPENDED      -> Set.of(ACTIVE, REVOKED);
            case FAILED         -> Set.of(DRAFT, REVOKED);
            case REVOKED        -> Set.of();
        };
    }

    public boolean canTransitionTo(SmsSenderStatus next) {
        return legalTransitions().contains(next);
    }

    public boolean isEditable() {
        return this == DRAFT || this == FAILED;
    }

    public boolean isTerminal() {
        return this == REVOKED;
    }

    public boolean acceptsOutbound() {
        return this == ACTIVE;
    }

    public boolean acceptsInbound() {
        return this == ACTIVE || this == PENDING_REVIEW || this == SUSPENDED;
    }
}
