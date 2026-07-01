package com.yayatechandinnovations.yayaagentic.knowledge.blob;

import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeBlobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodically reclaims orphaned upload bytes (design §11 / §14 step 5). Covers
 * both never-attached uploads and blobs a new source version stopped
 * referencing. Only affects DB-resident bytes directly; when the MinIO store is
 * active, object cleanup is handled where the metadata row is deleted — this
 * sweep targets the {@code knowledge_blobs} metadata table.
 *
 * <p>Gated by {@code yaya.agentic.knowledge.gc.enabled} (default on).
 */
@Component
@ConditionalOnProperty(name = "yaya.agentic.knowledge.gc.enabled",
        havingValue = "true", matchIfMissing = true)
public class BlobGcSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(BlobGcSweeper.class);

    private final KnowledgeBlobRepository blobs;
    private final BlobGcProperties props;

    public BlobGcSweeper(KnowledgeBlobRepository blobs, BlobGcProperties props) {
        this.blobs = blobs;
        this.props = props;
    }

    @Scheduled(
            fixedDelayString = "${yaya.agentic.knowledge.gc.interval-ms:3600000}",
            initialDelayString = "${yaya.agentic.knowledge.gc.initial-delay-ms:300000}")
    @Transactional
    public void sweep() {
        try {
            int removed = blobs.deleteOrphans(props.graceHours());
            if (removed > 0) {
                LOG.info("blob GC removed {} orphaned knowledge blob(s) older than {}h",
                        removed, props.graceHours());
            }
        } catch (Exception e) {
            LOG.warn("blob GC sweep failed: {}", e.toString());
        }
    }
}
