package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.channel.system.SenderTrust;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.outreach.ReplayGuard;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaLibraryService;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaLibraryService.MediaAsset;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Sender-scoped media upload for trusted integration senders (§10, GAP-U1):
 * lets an external system that already sends system messages upload an
 * attachment (a receipt, a document, an image) once and reference the
 * returned {@code assetId} in its ingest envelope's {@code media[]} —
 * without holding an ADMIN workspace session.
 *
 * <p>Same edge discipline as {@link SystemInboundController}: per-request
 * sender auth, scope check (baseline {@link SenderTrust#SEND_SCOPE} plus
 * {@link SenderTrust#MEDIA_SCOPE}), credential binding, replay guard, and
 * an opaque 404 for unknown/disabled senders. Bytes run the full knowledge
 * gauntlet in {@link MediaLibraryService#upload} (size cap → ClamAV →
 * content-type sniff → allow-list → per-tenant quota → SHA-256 de-dupe), so
 * a sender credential cannot bypass any media policy the admin path
 * enforces.
 */
@RestController
@RequestMapping("/v1/integrations/{tenantId}/senders/{senderId}/media")
public class SystemMediaController {

    private final Authenticator authenticator;
    private final SystemSenderService senders;
    private final ReplayGuard replayGuard;
    private final MediaLibraryService library;

    public SystemMediaController(Authenticator authenticator,
                                 SystemSenderService senders,
                                 ReplayGuard replayGuard,
                                 MediaLibraryService library) {
        this.authenticator = authenticator;
        this.senders = senders;
        this.replayGuard = replayGuard;
        this.library = library;
    }

    @PostMapping
    public Mono<UploadResponse> upload(@PathVariable("tenantId") String tenantId,
                                       @PathVariable("senderId") String senderId,
                                       @RequestBody UploadRequest body,
                                       ServerWebExchange exchange) {
        Ids.TenantId tenant = new Ids.TenantId(tenantId);
        Ids.SystemSenderId sid = new Ids.SystemSenderId(senderId);
        return Mono.fromSupplier(() -> {
            SystemSender sender = senders.find(tenant, sid)
                    .filter(SystemSender::enabled)
                    .orElseThrow(SystemMediaController::opaque404);

            Principal principal = authenticate(tenant, exchange);
            requireTrust(sender, principal);
            requireFreshRequest(tenant, sid, exchange);

            if (body == null || body.filename() == null || body.filename().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filename is required");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(body.contentBase64() == null ? "" : body.contentBase64());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentBase64 is not valid base64");
            }
            if (bytes.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty content");
            }
            MediaAsset asset = library.upload(tenant, body.filename().trim(), bytes, body.caption());
            return new UploadResponse(asset.id(), asset.filename(), asset.contentType(),
                    asset.sizeBytes(), asset.sha256());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ------------------------------ trust -------------------------------- */

    private Principal authenticate(Ids.TenantId tenant, ServerWebExchange exchange) {
        Map<String, String> headers = exchange.getRequest().getHeaders().toSingleValueMap();
        return authenticator.tryAuthenticate(new AuthContext(tenant, headers, Optional.empty()))
                .filter(p -> !isAnonymous(p))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "sender authentication failed"));
    }

    /** §4.1 plus the media add-on scope; credential binding as on ingest. */
    private void requireTrust(SystemSender sender, Principal principal) {
        SenderTrust trust = sender.trust();
        if (!principal.scopes().contains(SenderTrust.SEND_SCOPE)
                || !principal.scopes().contains(SenderTrust.MEDIA_SCOPE)
                || !principal.scopes().containsAll(trust.requiredScopes())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "insufficient scope");
        }
        if (!trust.credentialRef().isBlank()) {
            Object iss = principal.claims() == null ? null : principal.claims().get("iss");
            if (iss == null || !trust.credentialRef().equals(String.valueOf(iss))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "untrusted credential");
            }
        }
    }

    /** Mandatory nonce + timestamp; shares the ingest replay scope per sender. */
    private void requireFreshRequest(Ids.TenantId tenant, Ids.SystemSenderId sid,
                                     ServerWebExchange exchange) {
        String nonce = header(exchange, "X-Nonce");
        String ts = header(exchange, "X-Timestamp");
        long epochMs;
        try {
            epochMs = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "X-Timestamp (epoch millis) required");
        }
        String scope = tenant.value() + ":" + sid.value();
        ReplayGuard.Result r = replayGuard.check(scope, nonce, epochMs);
        if (r instanceof ReplayGuard.Result.Rejected rej) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "replay rejected: " + rej.reason());
        }
    }

    private static ResponseStatusException opaque404() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
    }

    private static boolean isAnonymous(Principal p) {
        String s = p.subject();
        return s == null || s.startsWith("anonymous-") || s.contains(":anon:");
    }

    private static String header(ServerWebExchange exchange, String name) {
        String v = exchange.getRequest().getHeaders().getFirst(name);
        return v == null ? "" : v.trim();
    }

    public record UploadRequest(String filename, String contentBase64, String caption) {}

    public record UploadResponse(String assetId, String filename, String contentType,
                                 long sizeBytes, String sha256) {}
}
