package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * How the platform obtained a sender's number ({@code sms-channel-design.md}
 * §7.1). {@link #PURCHASED} is the v1 path (buy fresh from Twilio inventory).
 * {@link #HOSTED} — porting a number the tenant already owns — is modelled now
 * but built post-MVP (§13, S6): it adds an LOA + proof-of-ownership document
 * step and an async port sub-flow before the sender enters {@code DRAFT}.
 */
public enum Acquisition {
    PURCHASED,
    HOSTED
}
