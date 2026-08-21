package com.yayatechandinnovations.yayaagentic.outreach.push;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointId;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPushTokenEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPushTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Real FCM transport (system-messaging-design.md §11 #3). Sends a
 * <strong>content-free</strong> multicast wake — a generic title/body plus a
 * {@code data} map the client uses to route an inbox drain — never the message
 * body or subject. Dead tokens (FCM {@code UNREGISTERED}/{@code
 * INVALID_ARGUMENT}) are pruned so the store self-heals. Only wired when a
 * {@link FirebaseMessaging} bean exists ({@link FirebaseConfig}).
 */
@Component
@ConditionalOnBean(FirebaseMessaging.class)
public class FirebaseFcmPushSender implements FcmPushSender {

    private static final Logger log = LoggerFactory.getLogger(FirebaseFcmPushSender.class);

    // Errors that mean "this token will never work again" → delete it.
    private static final Set<MessagingErrorCode> DEAD_TOKEN =
            EnumSet.of(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT);

    private final FirebaseMessaging messaging;
    private final ChatPushTokenRepository tokens;
    private final ChatEntryPointService entryPoints;

    public FirebaseFcmPushSender(FirebaseMessaging messaging, ChatPushTokenRepository tokens,
                                 ChatEntryPointService entryPoints) {
        this.messaging = messaging;
        this.tokens = tokens;
        this.entryPoints = entryPoints;
    }

    @Override
    public void send(Ids.TenantId tenant, String entryPointId, String recipientKey, String title,
                     List<ChatPushTokenEntity> devices) {
        if (devices.isEmpty()) {
            return;
        }
        // Index-aligned with the multicast token list so we can map failures back to rows.
        List<ChatPushTokenEntity> ordered = new ArrayList<>(devices);
        List<String> deviceTokens = ordered.stream().map(ChatPushTokenEntity::getToken).toList();

        // The client drains the inbox by public handle, not internal id — resolve it
        // so the tap can deep-link to the right tile. Best-effort: a missing handle
        // still wakes the device (it drains on next open).
        String handle = resolveHandle(tenant, entryPointId);

        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(deviceTokens)
                // Content-free: generic title/body — the real message rides the inbox, not the push.
                .setNotification(Notification.builder()
                        .setTitle(title == null || title.isBlank() ? "New message" : title)
                        .setBody("New message")
                        .build())
                .putData("type", "outreach_wake")
                .putData("entryPointId", entryPointId)
                .putData("handle", handle)
                .putData("sender", title == null ? "" : title)
                .build();

        try {
            BatchResponse response = messaging.sendEachForMulticast(message);
            pruneDeadTokens(recipientKey, ordered, response);
            if (response.getFailureCount() > 0) {
                log.debug("push wake [FCM] {} sent, {} failed for {}",
                        response.getSuccessCount(), response.getFailureCount(), recipientKey);
            }
        } catch (FirebaseMessagingException e) {
            // Non-fatal by SPI contract — the inbox row already holds the message.
            log.debug("push wake [FCM] send failed for {} (non-fatal)", recipientKey, e);
        }
    }

    private String resolveHandle(Ids.TenantId tenant, String entryPointId) {
        try {
            return entryPoints.requireForTenant(tenant, new ChatEntryPointId(entryPointId)).handle();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void pruneDeadTokens(String recipientKey, List<ChatPushTokenEntity> ordered,
                                 BatchResponse response) {
        List<SendResponse> responses = response.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            SendResponse r = responses.get(i);
            if (r.isSuccessful()) {
                continue;
            }
            FirebaseMessagingException ex = r.getException();
            if (ex != null && DEAD_TOKEN.contains(ex.getMessagingErrorCode())) {
                try {
                    tokens.delete(ordered.get(i));
                    log.debug("pruned dead FCM token for {}", recipientKey);
                } catch (RuntimeException prune) {
                    log.debug("failed to prune dead FCM token for {} (non-fatal)", recipientKey, prune);
                }
            }
        }
    }
}
