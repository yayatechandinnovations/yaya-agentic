package com.yayatechandinnovations.yayasales.payments.internal.onboarding;

/**
 * Normalized lifecycle of a merchant's connected PSP account, across providers.
 * Design §8.3, §9 ({@code merchant_psp_accounts}).
 *
 * <ul>
 *   <li>{@code PENDING} — no onboarding started (or a fresh row).</li>
 *   <li>{@code ONBOARDING} — the merchant was sent to the PSP's hosted flow;
 *       returned but not yet confirmed as chargeable (awaiting webhook/poll).</li>
 *   <li>{@code ACTIVE} — confirmed, {@code charges_enabled} — checkout may route
 *       funds to this account.</li>
 *   <li>{@code RESTRICTED} — connected but the PSP is withholding charges (more
 *       info needed); do NOT route funds.</li>
 *   <li>{@code DISABLED} — disconnected/revoked by the operator or the PSP.</li>
 * </ul>
 */
public enum MerchantAccountStatus {
    PENDING,
    ONBOARDING,
    ACTIVE,
    RESTRICTED,
    DISABLED
}
