package com.yayatechandinnovations.yayaagentic.outreach.push;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPushTokenEntity;

import java.util.List;

/**
 * Narrow seam that isolates the Firebase SDK from {@link CompositePushNotifier}:
 * given the already-resolved FCM device rows for a recipient, deliver one
 * content-free wake per device and prune tokens FCM reports as dead. Present as
 * a bean only when the FCM transport is configured
 * (chat-mobile-publishing-and-push-design.md §B.3).
 */
public interface FcmPushSender {

    void send(Ids.TenantId tenant, String entryPointId, String recipientKey, String title,
              List<ChatPushTokenEntity> devices);
}
