package com.yayatechandinnovations.yayaagentic.outreach.push;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPushTokenEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPushTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default {@link PushNotifier}: resolves a recipient's registered devices and
 * logs the wake per platform. The real transports — self-hosted Web Push/VAPID
 * for the PWA (fits the self-hosted-first constraint), FCM/APNs where a native
 * app requires it — drop in behind this SPI without touching the outreach flow.
 * Content-free: no message body ever leaves through the nudge.
 */
@Component
public class LoggingPushNotifier implements PushNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushNotifier.class);

    private final ChatPushTokenRepository tokens;

    public LoggingPushNotifier(ChatPushTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Override
    public void wake(Ids.TenantId tenant, String entryPointId, String recipientKey, String title) {
        List<ChatPushTokenEntity> devices = tokens
                .findByTenantIdAndEntryPointIdAndRecipientKey(tenant.value(), entryPointId, recipientKey);
        if (devices.isEmpty()) {
            log.debug("push wake: no registered devices for {} on {}", recipientKey, entryPointId);
            return;
        }
        for (ChatPushTokenEntity d : devices) {
            // A real transport POSTs a content-free payload here; we log until one lands.
            log.info("push wake [{}] → {} device(s not shown) for {} (\"{}\")",
                    d.getPlatform(), devices.size(), recipientKey, title);
        }
    }
}
