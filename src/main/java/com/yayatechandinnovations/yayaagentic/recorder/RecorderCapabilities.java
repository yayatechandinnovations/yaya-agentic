package com.yayatechandinnovations.yayaagentic.recorder;

import java.time.Duration;

public record RecorderCapabilities(
        boolean supportsSearch,
        boolean supportsRedaction,
        boolean supportsExport,
        boolean supportsArchive,
        Durability durability,
        Duration typicalWriteLatency
) {
    public enum Durability { STRONG, EVENTUAL }
}
