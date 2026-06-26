package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodically sweeps WhatsApp senders that are still mid-flight at the BSP
 * — anything in {@code SUBMITTING} or {@code PENDING_REVIEW} — and asks
 * Twilio for the latest verdict. Transition decisions live in
 * {@link WhatsAppSenderService}; this job is just the scheduler.
 *
 * <p>Defaults match {@code whatsapp-channel-design.md} §6.4: every 5 min.
 * The cadence is configurable via {@code yaya.agentic.channels.whatsapp.poller.*}
 * so a dev instance can either tighten it for tests or disable it entirely.
 */
@Component
public class SenderSyncJob {

    private static final Logger log = LoggerFactory.getLogger(SenderSyncJob.class);

    private final WhatsAppSenderService senders;
    private final ProviderCredsResolver credsResolver;
    private final boolean enabled;

    public SenderSyncJob(WhatsAppSenderService senders,
                         ProviderCredsResolver credsResolver,
                         @Value("${yaya.agentic.channels.whatsapp.poller.enabled:true}") boolean enabled) {
        this.senders = senders;
        this.credsResolver = credsResolver;
        this.enabled = enabled;
    }

    /**
     * Default: 5 min between sweeps, 30 s initial delay so the app finishes
     * booting before the first call. Both knobs configurable via property
     * placeholders so tests can opt into faster cadence without monkey-
     * patching the @Scheduled annotation.
     */
    @Scheduled(
            fixedDelayString = "${yaya.agentic.channels.whatsapp.poller.fixed-delay-ms:300000}",
            initialDelayString = "${yaya.agentic.channels.whatsapp.poller.initial-delay-ms:30000}"
    )
    public void sweep() {
        if (!enabled) return;
        if (!credsResolver.isReady()) {
            // Quiet skip — we already loud-logged "Twilio not configured" at
            // boot via TwilioStartupProbe. A noisy retry log every 5 min on
            // a dev box would be Worse Than Useless.
            return;
        }
        List<WhatsAppSender> inFlight = senders.findInFlight();
        if (inFlight.isEmpty()) return;
        log.info("WhatsApp sender poller: syncing {} in-flight senders", inFlight.size());
        for (WhatsAppSender sender : inFlight) {
            try {
                senders.syncOne(sender);
            } catch (RuntimeException e) {
                // Per-sender failures don't abort the sweep. The next pass
                // will pick the row back up; meanwhile we surface enough to
                // grep for in ops dashboards.
                log.warn("WhatsApp sender poller: sync failed for {} ({}): {}",
                        sender.id().value(), sender.tenant().value(), e.getMessage());
            }
        }
    }
}
