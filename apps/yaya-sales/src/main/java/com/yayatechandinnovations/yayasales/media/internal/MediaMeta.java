package com.yayatechandinnovations.yayasales.media.internal;

import java.time.Instant;

/** Listing projection — metadata without the image bytes. */
public record MediaMeta(String id, String storeId, String contentType, String filename,
                        int sizeBytes, Instant createdAt) {}
