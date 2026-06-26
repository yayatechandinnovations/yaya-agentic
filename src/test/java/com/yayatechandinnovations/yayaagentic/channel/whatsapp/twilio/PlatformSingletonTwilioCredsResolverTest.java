package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.ProviderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.BusinessProfile;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.OnboardingState;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The platform-singleton resolver returns the same yaml-bound creds for
 * every sender. When unconfigured, it throws {@code ProviderNotConfiguredException}
 * — never a stub or a no-op — so dispatch fails loudly rather than mystery.
 */
class PlatformSingletonTwilioCredsResolverTest {

    @Test
    void resolves_yaml_bound_creds_for_any_tenant() {
        TwilioProperties props = new TwilioProperties(
                "AC" + "1234567890".repeat(3) + "ab",
                "SK" + "1234567890".repeat(3) + "cd",
                "topsecret",
                "twilio-auth-token",
                "https://hooks.example",
                "MG0000000000000000000000000000ef00",
                null, null);
        PlatformSingletonTwilioCredsResolver resolver =
                new PlatformSingletonTwilioCredsResolver(props);

        TwilioCreds creds = (TwilioCreds) resolver.resolve(senderForTenant("acme-retail"));
        assertThat(creds.provider()).isEqualTo(ProviderId.TWILIO);
        assertThat(creds.accountSid()).isEqualTo(props.accountSid());
        assertThat(creds.apiKeySid()).isEqualTo(props.apiKeySid());
        assertThat(creds.apiKeySecret()).isEqualTo(props.apiKeySecret());
        assertThat(creds.messagingServiceSid()).isEqualTo(props.messagingServiceSid());

        // Different tenant → same creds (that's the singleton contract).
        TwilioCreds again = (TwilioCreds) resolver.resolve(senderForTenant("other-tenant"));
        assertThat(again).isEqualTo(creds);
    }

    @Test
    void messaging_service_sid_blank_becomes_null() {
        TwilioProperties props = new TwilioProperties(
                "AC1", "SK1", "secret", null, null, "   ", null, null);
        PlatformSingletonTwilioCredsResolver resolver =
                new PlatformSingletonTwilioCredsResolver(props);
        TwilioCreds creds = (TwilioCreds) resolver.resolve(senderForTenant("t"));
        assertThat(creds.messagingServiceSid()).isNull();
    }

    @Test
    void unconfigured_throws_typed_not_configured_exception() {
        TwilioProperties props = new TwilioProperties(null, null, null, null, null, null, null, null);
        PlatformSingletonTwilioCredsResolver resolver =
                new PlatformSingletonTwilioCredsResolver(props);
        assertThat(resolver.isReady()).isFalse();
        assertThatThrownBy(() -> resolver.resolve(senderForTenant("t")))
                .isInstanceOf(ProviderCredsResolver.ProviderNotConfiguredException.class)
                .hasMessageContaining("Twilio is not configured");
    }

    @Test
    void is_ready_when_three_fields_present_even_without_messaging_service_sid() {
        TwilioProperties props = new TwilioProperties("AC1", "SK1", "secret", null, null, null, null, null);
        assertThat(new PlatformSingletonTwilioCredsResolver(props).isReady()).isTrue();
    }

    private static WhatsAppSender senderForTenant(String tenant) {
        return new WhatsAppSender(
                new SenderId("snd_test"),
                new Ids.TenantId(tenant),
                "+15551234567",
                "Acme",
                null,
                new BusinessProfile("Acme Inc.", "Acme", "ops@acme.com",
                        "1 Apple Park Way", "https://acme.com", "RETAIL", "stuff"),
                SenderStatus.DRAFT,
                OnboardingState.empty(),
                "whsec-irrelevant",
                "en-US",
                Map.of(),
                Instant.now(),
                Instant.now(),
                null
        );
    }
}
