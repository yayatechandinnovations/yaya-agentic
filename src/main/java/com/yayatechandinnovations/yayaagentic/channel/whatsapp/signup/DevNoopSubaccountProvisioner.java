package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Dev / no-Twilio {@link TwilioSubaccountProvisioner}. Mints clearly fake
 * SIDs ({@code ACdev…}) so the embedded-signup flow can be driven end-to-end
 * on a laptop. Superseded by {@code TwilioSubaccountProvisionerClient}
 * whenever the parent Twilio account is configured.
 */
@Component
@ConditionalOnMissingBean(name = "twilioSubaccountProvisioner")
public class DevNoopSubaccountProvisioner implements TwilioSubaccountProvisioner {

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public SubaccountProvisionResult provision(String friendlyName) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        return new SubaccountProvisionResult.Created(
                "ACdev" + suffix, "devtoken-" + suffix);
    }
}
