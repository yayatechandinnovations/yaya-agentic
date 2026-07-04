package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaLibraryService;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaLibraryService.MediaAsset;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaTokenSigner;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Media library admin CRUD + the public token-gated serve endpoint (§10). Upload
 * runs the knowledge gauntlet; serving requires a short-lived signed token
 * (never a raw asset id), so links are unguessable and expire.
 */
@RestController
public class MediaController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final MediaLibraryService library;
    private final MediaTokenSigner signer;

    public MediaController(TenantGuard tenantGuard,
                           WorkspaceAccessHelper workspaceAccess,
                           MediaLibraryService library,
                           MediaTokenSigner signer) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.library = library;
        this.signer = signer;
    }

    /* ------------------------------- admin -------------------------------- */

    @GetMapping("/v1/admin/tenants/{tenantId}/media")
    public List<AssetResponse> list(@PathVariable("tenantId") String tenantId,
                                    ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        return library.list(new TenantId(tenantId)).stream().map(this::toResponse).toList();
    }

    @PostMapping("/v1/admin/tenants/{tenantId}/media")
    public Mono<AssetResponse> upload(@PathVariable("tenantId") String tenantId,
                                      @RequestBody UploadRequest req,
                                      ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return Mono.fromSupplier(() -> {
            if (req == null || req.filename() == null || req.filename().isBlank()) {
                throw AdminApiException.badRequest("missing_filename", "filename is required");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(req.contentBase64() == null ? "" : req.contentBase64());
            } catch (IllegalArgumentException e) {
                throw AdminApiException.badRequest("bad_base64", "contentBase64 is not valid base64");
            }
            return toResponse(library.upload(new TenantId(tenantId), req.filename().trim(), bytes, req.caption()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ---------------------------- public serve ---------------------------- */

    @GetMapping("/v1/media/{token}")
    public Mono<ResponseEntity<Resource>> serve(@PathVariable("token") String token) {
        return Mono.fromSupplier(() -> {
            MediaTokenSigner.Grant grant = signer.verify(token)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "expired or invalid link"));
            MediaAsset asset = library.find(grant.tenant(), grant.assetId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
            byte[] bytes;
            try (InputStream in = library.open(grant.tenant(), grant.assetId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"))) {
                bytes = StreamUtils.copyToByteArray(in);
            } catch (java.io.IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "read failed");
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(asset.contentType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + asset.filename() + "\"")
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=900")
                    .body((Resource) new ByteArrayResource(bytes));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private AssetResponse toResponse(MediaAsset a) {
        // A freshly-signed URL so the admin can preview immediately.
        return new AssetResponse(a.id(), a.filename(), a.contentType(), a.sizeBytes(),
                a.caption(), a.createdAt(), "/v1/media/" + signer.mint(a.tenant(), a.id()));
    }

    public record UploadRequest(String filename, String contentBase64, String caption) {}

    public record AssetResponse(String id, String filename, String contentType, long sizeBytes,
                                String caption, Instant createdAt, String signedUrl) {}
}
