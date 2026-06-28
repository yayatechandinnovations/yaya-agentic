package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * The kind of number backing a sender. v1 ships {@link #LONG_CODE} only;
 * {@link #SHORT_CODE} and {@link #TOLL_FREE} are the §4.2 seams (different
 * carrier provisioning — short codes skip A2P 10DLC but add a program brief;
 * toll-free uses Toll-Free Verification). Pinned here so the data model admits
 * them without a migration later.
 */
public enum SenderKind {
    LONG_CODE,
    SHORT_CODE,
    TOLL_FREE
}
