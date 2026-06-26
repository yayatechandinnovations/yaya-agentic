package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.yayatechandinnovations.yayaagentic.channel.ProviderCreds;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import org.springframework.stereotype.Component;

/**
 * v1 default {@link ProviderCredsResolver}: returns the same yaml-bound
 * platform credentials for every sender. The future BYO-Twilio resolver
 * ({@code HybridTwilioCredsResolver} in §7.3) sits on the same SPI and is a
 * one-{@code @Primary}-swap away — by design, this class doesn't need to know
 * a tenant could ever bring their own creds.
 *
 * <p>See {@code whatsapp-channel-design.md} §7.2.
 */
@Component
public class PlatformSingletonTwilioCredsResolver implements ProviderCredsResolver {

    private final TwilioProperties props;

    public PlatformSingletonTwilioCredsResolver(TwilioProperties props) {
        this.props = props;
    }

    @Override
    public ProviderCreds resolve(WhatsAppSender sender) {
        if (!props.isConfigured()) {
            throw new ProviderNotConfiguredException(
                    "Twilio is not configured — set yaya.agentic.channels.twilio.{account-sid,api-key-sid,api-key-secret}");
        }
        return new TwilioCreds(
                props.accountSid(),
                props.apiKeySid(),
                props.apiKeySecret(),
                emptyToNull(props.messagingServiceSid())
        );
    }

    @Override
    public boolean isReady() {
        return props.isConfigured();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
