package com.yayatechandinnovations.yayaagentic.knowledge.blob;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the orphaned-blob garbage collector — bound from
 * {@code yaya.agentic.knowledge.gc} (design §11). A blob is an orphan once no
 * live source version references it; {@code graceHours} keeps just-uploaded,
 * not-yet-attached blobs alive long enough to be saved into a source.
 */
@ConfigurationProperties(prefix = "yaya.agentic.knowledge.gc")
public record BlobGcProperties(Boolean enabled, Integer graceHours) {
    public BlobGcProperties {
        if (enabled == null) enabled = true;
        if (graceHours == null || graceHours < 1) graceHours = 24;
    }
}
