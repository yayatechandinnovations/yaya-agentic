package com.yayatechandinnovations.yayaagentic.outreach.push;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPushTokenEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPushTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The sole {@link PushNotifier} (SPI #14). Resolves a recipient's registered
 * devices once, groups them by {@code platform}, and dispatches each group to
 * its transport — FCM via {@link FcmPushSender} when configured; any platform
 * without a wired transport (e.g. {@code WEBPUSH} until §B.9 lands) is logged
 * and simply drains on next app open. Content-free throughout: the {@code
 * chat_inbox} row is the source of truth and a failed wake is never fatal
 * (system-messaging-design.md §11).
 */
@Component
public class CompositePushNotifier implements PushNotifier {

    private static final Logger log = LoggerFactory.getLogger(CompositePushNotifier.class);
    private static final String FCM = "FCM";

    private final ChatPushTokenRepository tokens;
    private final ObjectProvider<FcmPushSender> fcmSender;

    public CompositePushNotifier(ChatPushTokenRepository tokens, ObjectProvider<FcmPushSender> fcmSender) {
        this.tokens = tokens;
        this.fcmSender = fcmSender;
    }

    @Override
    public void wake(Ids.TenantId tenant, String entryPointId, String recipientKey, String title) {
        // Recipient-level, not entry-point-level: a device registers once and is
        // woken for any of the recipient's tiles; entryPointId rides the payload
        // so the client drains the right tile (see repository note).
        List<ChatPushTokenEntity> devices = tokens
                .findByTenantIdAndRecipientKey(tenant.value(), recipientKey);
        if (devices.isEmpty()) {
            log.debug("push wake: no registered devices for {} on {}", recipientKey, entryPointId);
            return;
        }

        Map<String, List<ChatPushTokenEntity>> byPlatform = devices.stream()
                .collect(Collectors.groupingBy(ChatPushTokenEntity::getPlatform));

        byPlatform.forEach((platform, group) -> {
            if (FCM.equals(platform)) {
                FcmPushSender sender = fcmSender.getIfAvailable();
                if (sender != null) {
                    sender.send(tenant, entryPointId, recipientKey, title, group);
                } else {
                    log.debug("push wake [FCM] no transport configured; {} device(s) for {} drain on open",
                            group.size(), recipientKey);
                }
            } else {
                // No transport wired for this platform yet — the inbox drain is the backstop.
                log.debug("push wake [{}] no transport; {} device(s) for {} drain on open",
                        platform, group.size(), recipientKey);
            }
        });
    }
}
