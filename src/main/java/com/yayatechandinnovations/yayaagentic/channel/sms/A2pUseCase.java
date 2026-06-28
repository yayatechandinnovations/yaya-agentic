package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * The registered use case for an {@link SmsCampaign}, mirroring TCR's standard
 * and special/restricted A2P 10DLC use-case taxonomy (see
 * {@code sms-channel-design.md} §6.3).
 *
 * <p>Most tenants pick a {@code standard} case and clear carrier review in
 * days. The {@code restricted} subset (gambling, sweepstakes, high-risk
 * financial) needs extra carrier approval, tenant-supplied licensing evidence,
 * and platform-operator review before submission — and some carriers refuse it
 * outright. This is where a lottery tenant lands: it is <em>one branch of an
 * enum</em>, not an assumption baked into the product.
 */
public enum A2pUseCase {

    // --- Standard use cases --------------------------------------------------
    ACCOUNT_NOTIFICATION(false),
    CUSTOMER_CARE(false),
    DELIVERY_NOTIFICATION(false),
    TWO_FACTOR_AUTH(false),
    MARKETING(false),
    POLLING_VOTING(false),
    PUBLIC_SERVICE_ANNOUNCEMENT(false),

    // --- Special / restricted use cases (extra carrier approval + evidence) --
    GAMBLING(true),
    SWEEPSTAKES(true),
    HIGH_RISK_FINANCIAL(true);

    private final boolean restricted;

    A2pUseCase(boolean restricted) {
        this.restricted = restricted;
    }

    /**
     * Restricted use cases gate into the §6.3 evidence-collection step and
     * default to {@code requires_operator_review = true} so a tenant cannot
     * push them to carriers without platform-operator release — protecting the
     * shared sending reputation for every other tenant on the account.
     */
    public boolean isRestricted() {
        return restricted;
    }
}
