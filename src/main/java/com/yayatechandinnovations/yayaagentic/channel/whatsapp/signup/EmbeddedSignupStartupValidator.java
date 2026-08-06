package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Fail-fast configuration check: when embedded signup is enabled, every
 * server-side prerequisite must be present at boot — a half-configured
 * instance would otherwise fail at the first tenant's onboarding attempt
 * with a confusing runtime error.
 */
@Component
public class EmbeddedSignupStartupValidator implements InitializingBean {

    private final EmbeddedSignupProperties signup;
    private final TwilioProperties twilio;
    private final JwtSecretCipher cipher;

    public EmbeddedSignupStartupValidator(EmbeddedSignupProperties signup,
                                          TwilioProperties twilio,
                                          JwtSecretCipher cipher) {
        this.signup = signup;
        this.twilio = twilio;
        this.cipher = cipher;
    }

    @Override
    public void afterPropertiesSet() {
        if (!signup.enabled()) {
            return;
        }
        if (!signup.isConfigured()) {
            throw new IllegalStateException(
                    "WhatsApp embedded signup is enabled but Meta configuration is missing — set "
                            + "META_APP_ID and META_WHATSAPP_CONFIGURATION_ID, or disable "
                            + "WHATSAPP_ONBOARDING_ENABLED.");
        }
        if (!twilio.isConfigured()) {
            throw new IllegalStateException(
                    "WhatsApp embedded signup is enabled but the parent Twilio account is not "
                            + "configured — set TWILIO_ACCOUNT_SID / TWILIO_API_KEY_SID / "
                            + "TWILIO_API_KEY_SECRET (and TWILIO_AUTH_TOKEN), or disable "
                            + "WHATSAPP_ONBOARDING_ENABLED.");
        }
        if (!cipher.isConfigured()) {
            throw new IllegalStateException(
                    "WhatsApp embedded signup is enabled but credential encryption is not "
                            + "configured — set YAYA_AGENTIC_SECRETS_ENCRYPTION_KEY (base64 32-byte "
                            + "AES key, `openssl rand -base64 32`), or disable "
                            + "WHATSAPP_ONBOARDING_ENABLED.");
        }
    }
}
