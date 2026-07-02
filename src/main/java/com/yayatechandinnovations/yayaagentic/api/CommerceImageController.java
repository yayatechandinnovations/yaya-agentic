package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.BlobStore;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Catalog/product image storage. Upload is operator-authenticated and stored via
 * the tenant-scoped {@link BlobStore}; the returned URL is served <b>publicly</b>
 * so it renders in a storefront the same as any product image. Only
 * {@code image/*} bytes are accepted and served — so this public path can never
 * be used to exfiltrate a knowledge document that happens to share the blob
 * store. See {@code ecommerce-chat-design.md} §7.2, §15.
 */
@RestController
@RequestMapping("/v1")
public class CommerceImageController {

    private static final long MAX_BYTES = 5 * 1024 * 1024;   // 5 MiB per image

    private final BlobStore blobStore;
    private final WorkspaceAccessHelper workspaceAccess;
    private final TenantGuard tenantGuard;

    public CommerceImageController(BlobStore blobStore, WorkspaceAccessHelper workspaceAccess,
                                   TenantGuard tenantGuard) {
        this.blobStore = blobStore;
        this.workspaceAccess = workspaceAccess;
        this.tenantGuard = tenantGuard;
    }

    @PostMapping(value = "/admin/commerce/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> upload(
            @RequestParam(value = "tenant", required = false) String tenant,
            @RequestPart("file") FilePart file,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.MEMBER);
        tenantGuard.requireWritable(resolved);
        String contentType = file.headers().getContentType() == null
                ? "application/octet-stream" : file.headers().getContentType().toString();
        if (!contentType.toLowerCase().startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only image/* uploads are allowed");
        }

        return DataBufferUtils.join(file.content())
                .map(CommerceImageController::toBytes)
                .publishOn(Schedulers.boundedElastic())
                .map(bytes -> {
                    if (bytes.length > MAX_BYTES) {
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds 5 MiB");
                    }
                    BlobStore.StoredBlob stored = blobStore.store(new TenantId(resolved),
                            file.filename(), contentType,
                            new java.io.ByteArrayInputStream(bytes), bytes.length);
                    return Map.of(
                            "blobId", stored.blobId(),
                            "url", "/v1/commerce/images/" + resolved + "/" + stored.blobId());
                });
    }

    /** Public read — catalog images are meant to be shown. Non-image blobs are refused. */
    @GetMapping("/commerce/images/{tenant}/{blobId}")
    public Mono<ResponseEntity<byte[]>> serve(@PathVariable("tenant") String tenant,
                                              @PathVariable("blobId") String blobId) {
        return Mono.fromCallable(() -> {
            TenantId t = new TenantId(tenant);
            BlobStore.BlobMetadata meta = blobStore.stat(t, blobId).orElse(null);
            if (meta == null || meta.contentType() == null
                    || !meta.contentType().toLowerCase().startsWith("image/")) {
                return ResponseEntity.notFound().<byte[]>build();
            }
            try (InputStream in = blobStore.open(t, blobId).orElse(null)) {
                if (in == null) return ResponseEntity.notFound().<byte[]>build();
                return ResponseEntity.ok()
                        .header("Content-Type", meta.contentType())
                        .header("Cache-Control", "public, max-age=86400")
                        .body(in.readAllBytes());
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "image read failed");
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static byte[] toBytes(DataBuffer buffer) {
        try {
            byte[] out = new byte[buffer.readableByteCount()];
            buffer.read(out);
            return out;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
