package com.yayatechandinnovations.yayaagentic.knowledge.ingest;

import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeSource;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceEntity;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls the ingestion queue and runs claimed jobs off the scheduler thread
 * (design §9). Claiming (short, transactional) happens on the scheduler tick;
 * the actual loader→chunk→embed pipeline — blocking and potentially slow — runs
 * on a dedicated executor so one large PDF never stalls the poller.
 *
 * <p>Gated by {@code yaya.agentic.knowledge.ingestion.worker-enabled} (default
 * on) so tests and one-shot tooling can disable background processing.
 */
@Component
@ConditionalOnProperty(name = "yaya.agentic.knowledge.ingestion.worker-enabled",
        havingValue = "true", matchIfMissing = true)
public class IngestionWorker {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionWorker.class);

    private final IngestionJobService jobService;
    private final KnowledgeSourceRepository sources;
    private final KnowledgeSourceRehydrator rehydrator;
    private final IngestionOrchestrator orchestrator;
    private final M0Catalog runtimeCatalog;
    private final TaskExecutor executor;
    private final IngestionProperties props;

    public IngestionWorker(IngestionJobService jobService,
                           KnowledgeSourceRepository sources,
                           KnowledgeSourceRehydrator rehydrator,
                           IngestionOrchestrator orchestrator,
                           M0Catalog runtimeCatalog,
                           @Qualifier("knowledgeIngestExecutor") TaskExecutor executor,
                           IngestionProperties props) {
        this.jobService = jobService;
        this.sources = sources;
        this.rehydrator = rehydrator;
        this.orchestrator = orchestrator;
        this.runtimeCatalog = runtimeCatalog;
        this.executor = executor;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${yaya.agentic.knowledge.ingestion.poll-interval-ms:3000}")
    public void poll() {
        List<IngestionJobService.Claim> claims;
        try {
            claims = jobService.claimBatch(props.batchSize());
        } catch (Exception e) {
            // A claim failure (e.g. DB blip) must not kill the scheduled task.
            LOG.warn("ingestion claim failed: {}", e.toString());
            return;
        }
        for (IngestionJobService.Claim claim : claims) {
            executor.execute(() -> run(claim));
        }
    }

    private void run(IngestionJobService.Claim claim) {
        try {
            KnowledgeSourceEntity entity = sources.findById(new KnowledgeSourceEntity.PK(
                    claim.tenantId(), claim.sourceId(), claim.sourceVersion())).orElse(null);
            if (entity == null) {
                jobService.markFailedOrRetry(claim.id(),
                        "source " + claim.sourceId() + " v" + claim.sourceVersion() + " not found");
                return;
            }
            KnowledgeSource source = rehydrator.rehydrate(entity);
            var result = orchestrator.ingest(source);
            runtimeCatalog.registerKnowledgeSource(source);
            if (result.error() != null) {
                jobService.markFailedOrRetry(claim.id(), result.error());
            } else {
                jobService.markDone(claim.id());
            }
        } catch (Exception e) {
            LOG.warn("ingestion job {} threw: {}", claim.id(), e.toString());
            jobService.markFailedOrRetry(claim.id(), e.toString());
        }
    }
}
