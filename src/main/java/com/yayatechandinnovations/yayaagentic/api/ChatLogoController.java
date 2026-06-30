package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.ChatEntryPointDtos;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatLogoService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.ChatLogoEntity;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Entry-point logo storage ({@code chat-channel-design.md} §13). Operators
 * upload an image (base64 JSON, ADMIN) and it is re-served from a stable public
 * URL — never hot-linked, content-type validated.
 */
@RestController
public class ChatLogoController {

    private final ChatLogoService logos;
    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;

    public ChatLogoController(ChatLogoService logos,
                              TenantGuard tenantGuard,
                              WorkspaceAccessHelper workspaceAccess) {
        this.logos = logos;
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
    }

    @PostMapping("/v1/admin/tenants/{tenantId}/chat-entry-points/logos")
    public ChatEntryPointDtos.LogoUploadResponse upload(
            @PathVariable("tenantId") String tenantId,
            @RequestBody ChatEntryPointDtos.LogoUploadRequest req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        if (req == null) throw AdminApiException.badRequest("missing_body", "request body required");
        ChatLogoService.Stored stored = logos.store(
                new Ids.TenantId(tenantId), req.contentType(), req.dataBase64());
        return new ChatEntryPointDtos.LogoUploadResponse(stored.logoId(), stored.url());
    }

    /** Public — the chat app and admin preview both load logos here. */
    @GetMapping("/v1/chat/logos/{id}")
    public Mono<ResponseEntity<byte[]>> serve(@PathVariable("id") String id) {
        return Mono.fromSupplier(() -> logos.find(id)
                .map(this::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build()));
    }

    private ResponseEntity<byte[]> ok(ChatLogoEntity logo) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(logo.getData());
    }
}
