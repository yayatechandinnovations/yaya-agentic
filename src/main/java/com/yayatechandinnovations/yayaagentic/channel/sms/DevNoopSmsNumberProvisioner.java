package com.yayatechandinnovations.yayaagentic.channel.sms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Dev / no-Twilio {@link SmsNumberProvisioner}. Pretends every number is
 * instantly provisioned and online, so an SMS sender can be driven through its
 * whole lifecycle on a laptop. Combined with {@link DevNoopA2pRegistrar}, a US
 * sender reaches ACTIVE once its (dev-auto-approved) campaign is APPROVED.
 *
 * <p>{@link ConditionalOnMissingBean} so the real {@code TwilioSmsNumberProvisioner}
 * supersedes it by simply existing. Synthetic SIDs are clearly fake
 * ({@code PNdev…}) so they can't be mistaken for live numbers in logs.
 */
@Component
@ConditionalOnMissingBean(name = "twilioSmsNumberProvisioner")
public class DevNoopSmsNumberProvisioner implements SmsNumberProvisioner {

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public SenderProvisionResult provision(SmsSender sender) {
        return new SenderProvisionResult.Accepted(
                "PNdev" + sender.id().value().replaceAll("[^A-Za-z0-9]", ""), "PENDING");
    }

    @Override
    public ProvisionPoll poll(SmsSender sender) {
        return new ProvisionPoll(true, null, null);   // provider always "ready" in dev
    }

    @Override
    public void revoke(SmsSender sender) {
        // no-op
    }
}
