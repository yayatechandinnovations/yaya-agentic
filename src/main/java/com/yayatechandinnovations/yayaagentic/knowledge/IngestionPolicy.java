package com.yayatechandinnovations.yayaagentic.knowledge;

import java.time.Duration;

public record IngestionPolicy(
        String chunkerName,
        int chunkSize,
        int chunkOverlap,
        String embeddingModel,
        Duration refreshInterval
) {
    public static IngestionPolicy defaults() {
        return new IngestionPolicy(
                "recursive-structural",
                900,
                100,
                "text-embedding-3-small",
                Duration.ofHours(24)
        );
    }
}
