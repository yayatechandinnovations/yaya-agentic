package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

/**
 * Lifecycle of a {@link WhatsAppMessagingAccount} (tenant WABA ↔ dedicated
 * Twilio subaccount). PROVISIONING → ACTIVE on successful subaccount
 * creation; DISCONNECTED is the operator-driven terminal state — the row is
 * kept as an audit record and its WABA stays claimed by the tenant.
 */
public enum MessagingAccountStatus {
    PROVISIONING,
    ACTIVE,
    SUSPENDED,
    DISCONNECTED;

    /** Only an ACTIVE account may authenticate API calls and sign webhooks. */
    public boolean isOperational() {
        return this == ACTIVE;
    }
}
