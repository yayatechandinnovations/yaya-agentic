package com.yayatechandinnovations.yayaagentic.recorder;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Compliance / lifecycle operation requests + results. Grouped here because
 * each is a small DTO and they're always co-used by the recorder SPI.
 */
public final class Operations {

    private Operations() {}

    public record RedactionRequest(
            String operatorSubject,
            String reason,
            List<String> jsonPathSelectors
    ) {}

    public record RedactionResult(int turnsAffected, int fieldsRedacted, Instant appliedAt) {}

    public record DeletionRequest(String operatorSubject, String reason) {}

    public record ExportRequest(Instant from, Instant to, String format) {}

    public record SessionExport(Ids.SessionId sessionId, byte[] payload, String contentType) {}

    public record ArchivePolicy(Duration olderThan, String tier) {}

    public record SessionEndContext(Instant endedAt, String reason) {}
}
