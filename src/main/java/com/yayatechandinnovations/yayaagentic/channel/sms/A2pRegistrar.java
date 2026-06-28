package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * Port abstracting the carrier-registry side of A2P 10DLC — Twilio TrustHub
 * (Secondary Customer Profile) + The Campaign Registry (Brand + Campaign). The
 * platform talks only to Twilio; this SPI keeps that BSP detail out of
 * {@link SmsComplianceService}, exactly the way {@code MessagingChannelProvider}
 * keeps Twilio's messaging API out of the sender service.
 *
 * <p>v1 ships {@link DevNoopA2pRegistrar} (auto-approves locally so the flow is
 * exercisable without Twilio). A {@code TwilioA2pRegistrar} lands as a later
 * increment and supersedes it — no change to the service or the state machine.
 *
 * <p>The {@code poll*} methods return the <em>internal</em> next status the
 * registry implies, so the poller (S1d) folds it back without re-deriving the
 * carrier-status mapping in two places.
 */
public interface A2pRegistrar {

    /** True when the registry is configured on this instance (Twilio creds +
     *  Primary Profile SID). Gates {@code submit} the same way
     *  {@code ProviderCredsResolver.isReady()} gates WhatsApp. */
    boolean isReady();

    A2pSubmitResult submitBrand(SmsBrand brand);

    A2pSubmitResult submitCampaign(SmsCampaign campaign, SmsBrand brand);

    /** Re-read a submitted Brand's carrier state. */
    BrandPoll pollBrand(SmsBrand brand);

    /** Re-read a submitted Campaign's carrier state. */
    CampaignPoll pollCampaign(SmsCampaign campaign);

    /** Poll verdict for a Brand: the internal status it now maps to, plus any
     *  TCR brand id assigned at verification and the latest failure detail. */
    record BrandPoll(BrandStatus status, String tcrBrandId,
                     String failureCode, String failureMessage) {}

    /** Poll verdict for a Campaign. */
    record CampaignPoll(CampaignStatus status,
                        String failureCode, String failureMessage) {}
}
