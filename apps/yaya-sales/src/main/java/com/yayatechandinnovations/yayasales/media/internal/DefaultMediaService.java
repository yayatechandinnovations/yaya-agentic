package com.yayatechandinnovations.yayasales.media.internal;

import com.yayatechandinnovations.yayasales.media.MediaService;
import com.yayatechandinnovations.yayasales.media.MediaView;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.MediaId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class DefaultMediaService implements MediaService {

    /** Browser-renderable image types only — media is served inline (§15). */
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/svg+xml");

    static final int MAX_BYTES = 5 * 1024 * 1024;

    private final MediaRepository media;
    private final StoreDirectory stores;

    public DefaultMediaService(MediaRepository media, StoreDirectory stores) {
        this.media = media;
        this.stores = stores;
    }

    @Override
    @Transactional
    public MediaView upload(StoreId store, String filename, String contentType, byte[] bytes) {
        stores.store(store).orElseThrow(() -> new ApiErrors.NotFoundException("store", store.value()));
        if (bytes == null || bytes.length == 0) {
            throw new ApiErrors.UnprocessableException("empty upload");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ApiErrors.UnprocessableException(
                    "image exceeds " + (MAX_BYTES / (1024 * 1024)) + "MB");
        }
        String normalized = contentType == null ? "" : contentType.toLowerCase().split(";")[0].trim();
        if (!IMAGE_TYPES.contains(normalized)) {
            throw new ApiErrors.UnprocessableException(
                    "unsupported content type '" + contentType + "' — images only");
        }
        MediaEntity entity = new MediaEntity(Ids.newId(), store.value(), normalized,
                filename, bytes, Instant.now());
        media.save(entity);
        return toView(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Stored> load(MediaId id) {
        return media.findById(id.value())
                .map(e -> new Stored(toView(e), e.getBytes()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MediaView> mediaOf(StoreId store) {
        return media.metaByStoreId(store.value()).stream()
                .map(m -> new MediaView(new MediaId(m.id()), new StoreId(m.storeId()),
                        m.contentType(), m.filename(), m.sizeBytes(), m.createdAt()))
                .toList();
    }

    @Override
    @Transactional
    public void delete(MediaId id, StoreId store) {
        MediaEntity entity = media.findById(id.value())
                .filter(e -> e.getStoreId().equals(store.value()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("media", id.value()));
        media.delete(entity);
    }

    private static MediaView toView(MediaEntity e) {
        return new MediaView(new MediaId(e.getId()), new StoreId(e.getStoreId()),
                e.getContentType(), e.getFilename(), e.getSizeBytes(), e.getCreatedAt());
    }
}
