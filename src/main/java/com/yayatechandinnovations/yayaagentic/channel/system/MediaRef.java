package com.yayatechandinnovations.yayaagentic.channel.system;

/**
 * A reference to an uploaded media asset on an ingest envelope (§5, §10). The
 * sender uploads once through the media gauntlet and references the resulting
 * {@code assetId} — the ingest body carries handles, not bytes, so one closure
 * PDF fans out to a whole shift.
 */
public record MediaRef(String assetId, String contentType, String filename, long sizeBytes) {

    public MediaRef {
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("assetId required");
        }
    }
}
