package com.yayatechandinnovations.yayasales.media;

import com.yayatechandinnovations.yayasales.platform.Ids.MediaId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.util.List;
import java.util.Optional;

/** Upload / serve / prune store media. Only images, capped in size (§15). */
public interface MediaService {

    MediaView upload(StoreId store, String filename, String contentType, byte[] bytes);

    Optional<Stored> load(MediaId id);

    List<MediaView> mediaOf(StoreId store);

    void delete(MediaId id, StoreId store);

    record Stored(MediaView meta, byte[] bytes) {}
}
