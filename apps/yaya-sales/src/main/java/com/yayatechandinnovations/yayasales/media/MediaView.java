package com.yayatechandinnovations.yayasales.media;

import com.yayatechandinnovations.yayasales.platform.Ids.MediaId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.time.Instant;

public record MediaView(
        MediaId id,
        StoreId store,
        String contentType,
        String filename,
        int sizeBytes,
        Instant createdAt) {

    /** The serving path — what product media_json / catalog cover_url store. */
    public String path() {
        return "/v1/sales/media/" + id.value();
    }
}
