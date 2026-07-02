package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.ChannelBindingDtos;
import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingId;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Optional;

/**
 * Admin CRUD for channel bindings — the rows that hand a WhatsApp sender's
 * inbound to a profile. Per {@code whatsapp-channel-design.md} §14.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/channel-bindings")
public class ChannelBindingController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final ChannelBindingService bindings;

    public ChannelBindingController(TenantGuard tenantGuard,
                                    WorkspaceAccessHelper workspaceAccess,
                                    ChannelBindingService bindings) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.bindings = bindings;
    }

    @GetMapping
    public List<ChannelBindingDtos.BindingResponse> list(@PathVariable("tenantId") String tenantId,
                                                         ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return bindings.listForTenant(new Ids.TenantId(tenantId)).stream()
                .map(ChannelBindingDtos.BindingResponse::of)
                .toList();
    }

    @PostMapping
    public ResponseEntity<ChannelBindingDtos.BindingResponse> create(
            @PathVariable("tenantId") String tenantId,
            @RequestBody ChannelBindingDtos.BindingRequest req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        validateCreate(req);

        ChannelBinding saved = bindings.create(
                new Ids.TenantId(tenantId),
                toEndpoint(req.endpoint()),
                new Ids.ProfileId(req.profileId(),
                        req.profileVersion() == null ? 1 : req.profileVersion()),
                Optional.ofNullable(req.profileVersion()),
                req.routing() == null ? new RoutingRule.MatchAny() : req.routing(),
                req.sessionPolicy() == null ? SessionPolicy.defaults() : req.sessionPolicy(),
                req.priority() == null ? 100 : req.priority(),
                req.authResolverId(),
                req.onUnresolved(),
                allowlistOf(req.allowedOverrideProfiles()),
                req.onOverrideDenied());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ChannelBindingDtos.BindingResponse.of(saved));
    }

    @PutMapping("/{bindingId}")
    public ChannelBindingDtos.BindingResponse update(@PathVariable("tenantId") String tenantId,
                                                     @PathVariable("bindingId") long bindingId,
                                                     @RequestBody ChannelBindingDtos.BindingUpdate req,
                                                     ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        if (req == null) {
            throw AdminApiException.badRequest("missing_body", "request body required");
        }
        if (req.profileId() == null || req.profileId().isBlank()) {
            throw AdminApiException.badRequest("bad_profile", "profileId required");
        }
        ChannelBinding saved = bindings.update(
                new Ids.TenantId(tenantId),
                new BindingId(bindingId),
                new Ids.ProfileId(req.profileId(),
                        req.profileVersion() == null ? 1 : req.profileVersion()),
                Optional.ofNullable(req.profileVersion()),
                req.routing() == null ? new RoutingRule.MatchAny() : req.routing(),
                req.sessionPolicy() == null ? SessionPolicy.defaults() : req.sessionPolicy(),
                req.priority() == null ? 100 : req.priority(),
                req.status() == null ? BindingStatus.ACTIVE : req.status(),
                req.authResolverId(),
                req.onUnresolved(),
                allowlistOf(req.allowedOverrideProfiles()),
                req.onOverrideDenied());
        return ChannelBindingDtos.BindingResponse.of(saved);
    }

    @DeleteMapping("/{bindingId}")
    public ResponseEntity<Void> delete(@PathVariable("tenantId") String tenantId,
                                       @PathVariable("bindingId") long bindingId,
                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        bindings.deactivate(new Ids.TenantId(tenantId), new BindingId(bindingId));
        return ResponseEntity.noContent().build();
    }

    /* ---------------------------- helpers --------------------------------- */

    private static void validateCreate(ChannelBindingDtos.BindingRequest req) {
        if (req == null) {
            throw AdminApiException.badRequest("missing_body", "request body required");
        }
        if (req.endpoint() == null || req.endpoint().kind() == null
                || req.endpoint().id() == null) {
            throw AdminApiException.badRequest("bad_endpoint",
                    "endpoint.kind and endpoint.id required");
        }
        if (req.profileId() == null || req.profileId().isBlank()) {
            throw AdminApiException.badRequest("bad_profile", "profileId required");
        }
    }

    private static java.util.Set<String> allowlistOf(List<String> keys) {
        return keys == null ? java.util.Set.of() : java.util.Set.copyOf(keys);
    }

    private static ChannelEndpointRef toEndpoint(ChannelBindingDtos.EndpointRequest ep) {
        return switch (ep.kind()) {
            case "WHATSAPP_SENDER" ->
                    new ChannelEndpointRef.WhatsAppSenderRef(new SenderId(ep.id()));
            case "SMS_SENDER" ->
                    new ChannelEndpointRef.SmsSenderRef(
                            new com.yayatechandinnovations.yayaagentic.channel.sms.SmsSenderId(ep.id()));
            default -> throw AdminApiException.badRequest("bad_endpoint_kind",
                    "unknown endpoint kind: " + ep.kind());
        };
    }
}
