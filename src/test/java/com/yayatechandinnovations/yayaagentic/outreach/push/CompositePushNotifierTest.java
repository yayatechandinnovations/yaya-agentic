package com.yayatechandinnovations.yayaagentic.outreach.push;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPushTokenEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPushTokenRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CompositePushNotifierTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");
    private static final String ENTRY = "ep-1";
    private static final String RECIPIENT = "principal:sub-123";
    private static final String TITLE = "Acme POS";

    private final ChatPushTokenRepository tokens = mock(ChatPushTokenRepository.class);

    @SuppressWarnings("unchecked")
    private CompositePushNotifier notifier(FcmPushSender sender) {
        ObjectProvider<FcmPushSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new CompositePushNotifier(tokens, provider);
    }

    private ChatPushTokenEntity device(String platform, String token) {
        return new ChatPushTokenEntity(TENANT.value(), ENTRY, RECIPIENT, platform, token);
    }

    private void haveDevices(ChatPushTokenEntity... devices) {
        when(tokens.findByTenantIdAndRecipientKey(TENANT.value(), RECIPIENT))
                .thenReturn(List.of(devices));
    }

    @Test
    @SuppressWarnings("unchecked")
    void routesOnlyFcmDevicesToTheFcmSender() {
        haveDevices(device("FCM", "tok-a"), device("WEBPUSH", "sub-b"), device("FCM", "tok-c"));
        FcmPushSender sender = mock(FcmPushSender.class);

        notifier(sender).wake(TENANT, ENTRY, RECIPIENT, TITLE);

        ArgumentCaptor<List<ChatPushTokenEntity>> group = ArgumentCaptor.forClass(List.class);
        verify(sender).send(eq(TENANT), eq(ENTRY), eq(RECIPIENT), eq(TITLE), group.capture());
        assertThat(group.getValue())
                .extracting(ChatPushTokenEntity::getToken)
                .containsExactlyInAnyOrder("tok-a", "tok-c");
        assertThat(group.getValue())
                .allMatch(d -> d.getPlatform().equals("FCM"));
    }

    @Test
    void wakeCarriesNoMessageBody() {
        // The SPI signature has no body parameter, so the only text the composite can
        // forward is the sender display name (title). Content-free by construction.
        haveDevices(device("FCM", "tok-a"));
        FcmPushSender sender = mock(FcmPushSender.class);

        notifier(sender).wake(TENANT, ENTRY, RECIPIENT, TITLE);

        verify(sender).send(any(), any(), any(), eq(TITLE), any());
    }

    @Test
    void fcmDevicesWithoutTransportAreNoOp() {
        haveDevices(device("FCM", "tok-a"));

        // Sender absent (FCM not configured) — must not throw; drains on open.
        notifier(null).wake(TENANT, ENTRY, RECIPIENT, TITLE);
    }

    @Test
    void noDevicesSkipsTheSender() {
        haveDevices();
        FcmPushSender sender = mock(FcmPushSender.class);

        notifier(sender).wake(TENANT, ENTRY, RECIPIENT, TITLE);

        verifyNoInteractions(sender);
    }

    @Test
    void nonFcmPlatformsAreNotSentToFcm() {
        haveDevices(device("WEBPUSH", "sub-b"));
        FcmPushSender sender = mock(FcmPushSender.class);

        notifier(sender).wake(TENANT, ENTRY, RECIPIENT, TITLE);

        verify(sender, never()).send(any(), any(), any(), any(), any());
    }
}
