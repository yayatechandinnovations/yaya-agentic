package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * Port abstracting the BSP side of SMS <em>number</em> provisioning — buying or
 * hosting the number, wiring its inbound webhook, and attaching it to the
 * campaign's Messaging Service. Kept separate from the WhatsApp-typed
 * {@code ProviderCredsResolver}/{@code MessagingChannelProvider} on purpose: those
 * are typed to {@code WhatsAppSender}, and forcing SMS through them now would
 * require the inbound/outbound generalization (S3) before this layer can exist.
 *
 * <p>v1 ships {@link DevNoopSmsNumberProvisioner} (always "provisioned" locally).
 * The real {@code TwilioSmsNumberProvisioner} drops in behind this port later —
 * no change to {@link SmsSenderService} or the sender state machine.
 *
 * <p>{@link #poll} reports only the <em>provider's</em> view of the number;
 * whether the sender may actually go ACTIVE additionally depends on the
 * campaign-APPROVED / bundle gate, which {@link SmsSenderService} owns (§5.2).
 */
public interface SmsNumberProvisioner {

    /** True when SMS provisioning is configured (Twilio creds present). */
    boolean isReady();

    SenderProvisionResult provision(SmsSender sender);

    ProvisionPoll poll(SmsSender sender);

    void revoke(SmsSender sender);

    /**
     * Provider-side status of a submitted sender.
     *
     * @param providerReady   the number is provisioned and online at the BSP
     * @param failureCode     non-null when the BSP has failed the sender
     * @param failureMessage  human-readable failure detail
     */
    record ProvisionPoll(boolean providerReady, String failureCode, String failureMessage) {}
}
