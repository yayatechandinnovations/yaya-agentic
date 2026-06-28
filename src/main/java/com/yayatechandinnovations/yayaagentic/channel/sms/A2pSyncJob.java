package com.yayatechandinnovations.yayaagentic.channel.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodically sweeps A2P 10DLC Brands and Campaigns that are still mid-flight
 * at the carrier registry — anything {@code SUBMITTING} or in a {@code PENDING_*}
 * state — and asks the registry for the latest verdict. Transition decisions
 * live in {@link SmsComplianceService}; this job is just the scheduler.
 *
 * <p>The SMS analogue of {@code channel.whatsapp.SenderSyncJob} — same cadence
 * defaults (every 5 min, 30 s initial delay) and the same per-row isolation so
 * one bad row never aborts the sweep. One job, two table sweeps
 * ({@code sms-channel-design.md} §6.2: "one job, three table sweeps" once the
 * sender poller folds in at S2).
 */
@Component
public class A2pSyncJob {

    private static final Logger log = LoggerFactory.getLogger(A2pSyncJob.class);

    private final SmsComplianceService compliance;
    private final A2pRegistrar registrar;
    private final boolean enabled;

    public A2pSyncJob(SmsComplianceService compliance,
                      A2pRegistrar registrar,
                      @Value("${yaya.agentic.channels.sms.a2p-poller.enabled:true}") boolean enabled) {
        this.compliance = compliance;
        this.registrar = registrar;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${yaya.agentic.channels.sms.a2p-poller.fixed-delay-ms:300000}",
            initialDelayString = "${yaya.agentic.channels.sms.a2p-poller.initial-delay-ms:30000}"
    )
    public void sweep() {
        if (!enabled) return;
        if (!registrar.isReady()) return;   // quiet skip — registry not configured

        List<SmsBrand> brands = compliance.brandsInFlight();
        for (SmsBrand brand : brands) {
            try {
                compliance.syncBrand(brand);
            } catch (RuntimeException e) {
                log.warn("A2P poller: brand sync failed for {} ({}): {}",
                        brand.id().value(), brand.tenant().value(), e.getMessage());
            }
        }

        List<SmsCampaign> campaigns = compliance.campaignsInFlight();
        for (SmsCampaign campaign : campaigns) {
            try {
                compliance.syncCampaign(campaign);
            } catch (RuntimeException e) {
                log.warn("A2P poller: campaign sync failed for {} ({}): {}",
                        campaign.id().value(), campaign.tenant().value(), e.getMessage());
            }
        }

        if (!brands.isEmpty() || !campaigns.isEmpty()) {
            log.info("A2P poller: swept {} brand(s), {} campaign(s)", brands.size(), campaigns.size());
        }
    }
}
