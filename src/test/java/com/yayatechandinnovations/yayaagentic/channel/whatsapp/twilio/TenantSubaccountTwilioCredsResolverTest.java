package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.BusinessProfile;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.OnboardingState;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.MessagingAccountStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.WhatsAppMessagingAccount;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.WhatsAppMessagingAccountService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The §7.3 seam in isolation: subaccount-owned senders resolve their
 * dedicated credentials; legacy senders fall back to the platform
 * singleton; cross-tenant inconsistencies fail closed.
 */
class TenantSubaccountTwilioCredsResolverTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("t-1");
    private static final Ids.TenantId OTHER = new Ids.TenantId("t-2");

    private TwilioProperties props;
    private WhatsAppMessagingAccountService accounts;
    private TenantSubaccountTwilioCredsResolver resolver;

    @BeforeEach
    void setUp() {
        props = new TwilioProperties(
                "ACparent000000000000000000000000aa", "SKparent0000000000000000000000bb",
                "parent-secret", "parent-token", "https://hooks.example", "MGpool", null, null);
        accounts = mock(WhatsAppMessagingAccountService.class);
        resolver = new TenantSubaccountTwilioCredsResolver(
                new PlatformSingletonTwilioCredsResolver(props), accounts);
    }

    @Test
    void legacy_sender_resolves_platform_credentials() {
        TwilioCreds creds = (TwilioCreds) resolver.resolve(sender(null));
        assertThat(creds.accountSid()).isEqualTo("ACparent000000000000000000000000aa");
        assertThat(creds.apiKeySid()).isEqualTo("SKparent0000000000000000000000bb");
        assertThat(creds.messagingServiceSid()).isEqualTo("MGpool");
    }

    @Test
    void subaccount_sender_resolves_subaccount_credentials_without_messaging_service() {
        when(accounts.findById("wma_1")).thenReturn(Optional.of(account(TENANT,
                MessagingAccountStatus.ACTIVE, "ACsub00000000000000000000000000cc")));
        when(accounts.decryptAuthToken("wma_1")).thenReturn("sub-token");

        TwilioCreds creds = (TwilioCreds) resolver.resolve(sender("wma_1"));

        assertThat(creds.accountSid()).isEqualTo("ACsub00000000000000000000000000cc");
        assertThat(creds.authToken()).isEqualTo("sub-token");
        assertThat(creds.apiKeySid()).isNull();
        // Parent Messaging Service SIDs are not addressable from a
        // subaccount — dispatch must use From addressing.
        assertThat(creds.messagingServiceSid()).isNull();
    }

    @Test
    void tenant_mismatch_fails_closed_without_platform_fallback() {
        when(accounts.findById("wma_1")).thenReturn(Optional.of(account(OTHER,
                MessagingAccountStatus.ACTIVE, "ACsub00000000000000000000000000cc")));
        assertThatThrownBy(() -> resolver.resolve(sender("wma_1")))
                .isInstanceOf(ProviderCredsResolver.ProviderNotConfiguredException.class)
                .hasMessageContaining("tenant mismatch");
    }

    @Test
    void disconnected_account_fails_closed() {
        when(accounts.findById("wma_1")).thenReturn(Optional.of(account(TENANT,
                MessagingAccountStatus.DISCONNECTED, "ACsub00000000000000000000000000cc")));
        assertThatThrownBy(() -> resolver.resolve(sender("wma_1")))
                .isInstanceOf(ProviderCredsResolver.ProviderNotConfiguredException.class)
                .hasMessageContaining("not operational");
    }

    @Test
    void missing_account_fails_closed() {
        when(accounts.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(sender("wma_missing")))
                .isInstanceOf(ProviderCredsResolver.ProviderNotConfiguredException.class);
    }

    private static WhatsAppSender sender(String messagingAccountId) {
        return new WhatsAppSender(
                new SenderId("snd_test"), TENANT, "+15550001111", "Test Co", null,
                new BusinessProfile("Test Inc.", "Test Co", "ops@test.example",
                        null, null, "RETAIL", null, null),
                SenderStatus.ACTIVE, OnboardingState.empty(), "secret", "en-US",
                Map.of(), Instant.now(), Instant.now(), null, null,
                null, null, messagingAccountId);
    }

    private static WhatsAppMessagingAccount account(Ids.TenantId tenant,
                                                    MessagingAccountStatus status, String sid) {
        return new WhatsAppMessagingAccount("wma_1", tenant, "1110001110001",
                sid, "yaya:t-1:Test", status, Instant.now(), Instant.now());
    }
}
