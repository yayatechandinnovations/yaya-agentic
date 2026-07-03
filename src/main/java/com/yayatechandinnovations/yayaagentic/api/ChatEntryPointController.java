package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.ChatEntryPointDtos;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointId;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * Operator-facing chat entry point CRUD + lifecycle
 * ({@code chat-channel-design.md} §9, §12), mirroring {@code SmsSenderController}.
 * VIEWER may read; ADMIN may create / edit / drive the publish→pause→deactivate
 * lifecycle and rotate the public handle.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/chat-entry-points")
public class ChatEntryPointController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final ChatEntryPointService entryPoints;
    private final String deepLinkBase;

    public ChatEntryPointController(TenantGuard tenantGuard,
                                    WorkspaceAccessHelper workspaceAccess,
                                    ChatEntryPointService entryPoints,
                                    @Value("${yaya.agentic.chat.deep-link-base:https://chat.yaya.app/e/}") String deepLinkBase) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.entryPoints = entryPoints;
        this.deepLinkBase = normalizeDeepLinkBase(deepLinkBase);
    }

    /**
     * The public entry route is {@code /e/{handle}} (chat-channel-design.md §11).
     * Operators frequently set the base to just the host (e.g.
     * {@code https://chat.example.com}); without the {@code /e/} segment the
     * emitted link — and the QR the admin renders from it — resolves to
     * {@code /e_<handle>}, which the app has no route for. Normalize so the base
     * always carries exactly one trailing {@code /e/} regardless of how it was set.
     */
    static String normalizeDeepLinkBase(String configured) {
        String base = (configured == null || configured.isBlank())
                ? "https://chat.yaya.app/e/" : configured.trim();
        base = base.replaceAll("/+$", "");
        if (!base.endsWith("/e")) base = base + "/e";
        return base + "/";
    }

    @GetMapping
    public List<ChatEntryPointDtos.EntryPointResponse> list(@PathVariable("tenantId") String tenantId,
                                                            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return entryPoints.listForTenant(new Ids.TenantId(tenantId)).stream()
                .map(e -> ChatEntryPointDtos.EntryPointResponse.of(e, deepLinkBase))
                .toList();
    }

    @GetMapping("/{id}")
    public ChatEntryPointDtos.EntryPointResponse get(@PathVariable("tenantId") String tenantId,
                                                     @PathVariable("id") String id,
                                                     ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return resp(entryPoints.requireForTenant(new Ids.TenantId(tenantId), new ChatEntryPointId(id)));
    }

    @PostMapping
    public ResponseEntity<ChatEntryPointDtos.EntryPointResponse> create(
            @PathVariable("tenantId") String tenantId,
            @RequestBody ChatEntryPointDtos.CreateRequest req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        if (req == null) throw AdminApiException.badRequest("missing_body", "request body required");
        var cmd = new ChatEntryPointService.CreateCommand(
                req.profileId(), req.profileVersion(), req.displayName(), req.logoUrl(),
                req.accentColorHex(), req.welcomeText(), req.defaultLocale(),
                req.tagline(), req.discoverable(),
                ChatEntryPointDtos.parseAuthMode(req.authMode()),
                req.authConfig() == null ? null : req.authConfig().toDomain(),
                req.abuse() == null ? null : req.abuse().toDomain(),
                req.requiresVerifiedPhone(),
                allowlistOf(req.allowedOverrideProfiles()),
                req.onOverrideDenied(),
                req.startFlowId());
        var created = entryPoints.createDraft(new Ids.TenantId(tenantId), cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp(created));
    }

    @PutMapping("/{id}")
    public ChatEntryPointDtos.EntryPointResponse update(@PathVariable("tenantId") String tenantId,
                                                        @PathVariable("id") String id,
                                                        @RequestBody ChatEntryPointDtos.UpdateRequest req,
                                                        ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        if (req == null) throw AdminApiException.badRequest("missing_body", "request body required");
        var cmd = new ChatEntryPointService.UpdateCommand(
                req.profileId(), req.profileVersion(),
                req.displayName(), req.logoUrl(), req.accentColorHex(), req.welcomeText(),
                req.defaultLocale(), req.tagline(), req.discoverable(),
                req.authMode() == null ? null : ChatEntryPointDtos.parseAuthMode(req.authMode()),
                req.authConfig() == null ? null : req.authConfig().toDomain(),
                req.abuse() == null ? null : req.abuse().toDomain(),
                req.requiresVerifiedPhone(),
                req.allowedOverrideProfiles() == null ? null : allowlistOf(req.allowedOverrideProfiles()),
                req.onOverrideDenied(),
                req.startFlowId());
        return resp(entryPoints.updateBranding(new Ids.TenantId(tenantId), new ChatEntryPointId(id), cmd));
    }

    @PostMapping("/{id}/publish")
    public ChatEntryPointDtos.EntryPointResponse publish(@PathVariable("tenantId") String tenantId,
                                                         @PathVariable("id") String id,
                                                         ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return resp(entryPoints.publish(new Ids.TenantId(tenantId), new ChatEntryPointId(id)));
    }

    @PostMapping("/{id}/pause")
    public ChatEntryPointDtos.EntryPointResponse pause(@PathVariable("tenantId") String tenantId,
                                                       @PathVariable("id") String id,
                                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return resp(entryPoints.pause(new Ids.TenantId(tenantId), new ChatEntryPointId(id)));
    }

    @PostMapping("/{id}/resume")
    public ChatEntryPointDtos.EntryPointResponse resume(@PathVariable("tenantId") String tenantId,
                                                        @PathVariable("id") String id,
                                                        ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return resp(entryPoints.resume(new Ids.TenantId(tenantId), new ChatEntryPointId(id)));
    }

    @PostMapping("/{id}/rotate-handle")
    public ChatEntryPointDtos.EntryPointResponse rotateHandle(@PathVariable("tenantId") String tenantId,
                                                              @PathVariable("id") String id,
                                                              ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return resp(entryPoints.rotateHandle(new Ids.TenantId(tenantId), new ChatEntryPointId(id)));
    }

    @DeleteMapping("/{id}")
    public ChatEntryPointDtos.EntryPointResponse deactivate(@PathVariable("tenantId") String tenantId,
                                                            @PathVariable("id") String id,
                                                            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return resp(entryPoints.deactivate(new Ids.TenantId(tenantId), new ChatEntryPointId(id)));
    }

    private ChatEntryPointDtos.EntryPointResponse resp(
            com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint e) {
        return ChatEntryPointDtos.EntryPointResponse.of(e, deepLinkBase);
    }

    private static java.util.Set<String> allowlistOf(List<String> keys) {
        return keys == null ? java.util.Set.of() : java.util.Set.copyOf(keys);
    }
}
