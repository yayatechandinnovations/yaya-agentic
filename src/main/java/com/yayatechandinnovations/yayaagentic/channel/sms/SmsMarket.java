package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * The market a sender's number lives in. Drives the compliance path
 * ({@code sms-channel-design.md} §7): {@link #US} requires an A2P 10DLC
 * campaign; {@link #DO} (Dominican Republic) requires a Twilio regulatory
 * bundle and has the sender-ID-overwrite reality surfaced in the UI.
 */
public enum SmsMarket {

    US,
    DO;

    /** US senders register under A2P 10DLC; non-US markets do not. */
    public boolean requiresA2pCampaign() {
        return this == US;
    }

    /** Markets where a Twilio regulatory bundle gates number provisioning. */
    public boolean requiresRegulatoryBundle() {
        return this == DO;
    }
}
