package com.yayatechandinnovations.yayasales.media.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MediaRepository extends JpaRepository<MediaEntity, String> {

    /** Listing must not drag every image's bytes out of the DB. */
    @Query("""
            select new com.yayatechandinnovations.yayasales.media.internal.MediaMeta(
                m.id, m.storeId, m.contentType, m.filename, m.sizeBytes, m.createdAt)
            from MediaEntity m where m.storeId = :storeId order by m.createdAt desc""")
    List<MediaMeta> metaByStoreId(String storeId);
}
