package com.yayatechandinnovations.yayaagentic.outreach.media;

import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.system.Attachment;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.BlobStore;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.KnowledgeUploadService;
import com.yayatechandinnovations.yayaagentic.persistence.MediaAssetEntity;
import com.yayatechandinnovations.yayaagentic.persistence.MediaAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Logical media over the knowledge {@link BlobStore} (§10 #1). Upload runs the
 * SAME gauntlet as knowledge files — size cap → ClamAV → content-type sniff →
 * allow-list → per-tenant quota → SHA-256 de-dupe — via {@link
 * KnowledgeUploadService}, with the allow-list and the image cap being the
 * outreach ones ({@link OutreachMediaProperties}: PDF, PNG and JPEG by default,
 * images at most 5 MiB); the asset row is the tenant-scoped, filename-carrying
 * handle the ingest envelope references.
 */
@Service
public class MediaLibraryService {

    private final KnowledgeUploadService uploads;
    private final BlobStore blobStore;
    private final MediaAssetRepository assets;
    private final KnowledgeUploadService.UploadPolicy policy;

    public MediaLibraryService(KnowledgeUploadService uploads,
                               BlobStore blobStore,
                               MediaAssetRepository assets,
                               OutreachMediaProperties mediaProps) {
        this.uploads = uploads;
        this.blobStore = blobStore;
        this.assets = assets;
        OutreachMediaProperties props = mediaProps == null
                ? new OutreachMediaProperties(null, null) : mediaProps;
        long documentCap = uploads.defaultPolicy().maxBytesFor().applyAsLong("application/pdf");
        this.policy = new KnowledgeUploadService.UploadPolicy(props::isAllowed,
                ct -> OutreachMediaProperties.isImage(ct) ? props.maxImageBytes() : documentCap);
    }

    /** Store bytes through the gauntlet (with the outreach type policy) and register a logical asset. */
    @Transactional
    public MediaAsset upload(Ids.TenantId tenant, String filename, byte[] bytes, String caption) {
        var stored = uploads.storeAll(tenant, List.of(
                new KnowledgeUploadService.PendingFile(filename, bytes)), policy);
        var f = stored.get(0);
        String id = "media_" + UUID.randomUUID().toString().replace("-", "");
        MediaAssetEntity e = new MediaAssetEntity(id, tenant.value(), f.blobId(),
                f.filename(), f.contentType(), f.sizeBytes(), f.sha256());
        if (caption != null && !caption.isBlank()) e.setCaption(caption.trim());
        return toDomain(assets.save(e));
    }

    public List<MediaAsset> list(Ids.TenantId tenant) {
        return assets.findByTenantIdOrderByCreatedAtDesc(tenant.value()).stream()
                .map(this::toDomain).toList();
    }

    public Optional<MediaAsset> find(Ids.TenantId tenant, String assetId) {
        return assets.findByTenantIdAndId(tenant.value(), assetId).map(this::toDomain);
    }

    public MediaAsset require(Ids.TenantId tenant, String assetId) {
        return find(tenant, assetId).orElseThrow(() -> AdminApiException.notFound(
                "media_not_found", "no media asset '" + assetId + "'"));
    }

    /** Stream the underlying bytes (behind a verified signed token, §10 #2). */
    public Optional<InputStream> open(Ids.TenantId tenant, String assetId) {
        return assets.findByTenantIdAndId(tenant.value(), assetId)
                .flatMap(e -> blobStore.open(tenant, e.getBlobId()));
    }

    /** Build the sealed attachment type for an asset (drives renderer/provider). */
    public Attachment attachmentOf(MediaAsset a) {
        return Attachment.of(a.id(), a.contentType(), a.sizeBytes(), a.filename());
    }

    private MediaAsset toDomain(MediaAssetEntity e) {
        return new MediaAsset(e.getId(), new Ids.TenantId(e.getTenantId()), e.getBlobId(),
                e.getFilename(), e.getContentType(), e.getSizeBytes(), e.getSha256(),
                e.getCaption(), e.getCreatedAt() == null ? Instant.now() : e.getCreatedAt().toInstant());
    }

    public record MediaAsset(String id, Ids.TenantId tenant, String blobId, String filename,
                             String contentType, long sizeBytes, String sha256, String caption,
                             Instant createdAt) {}
}
