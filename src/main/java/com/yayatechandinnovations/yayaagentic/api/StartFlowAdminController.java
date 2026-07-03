package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.StartFlowDtos;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.startflow.FlowPolicy;
import com.yayatechandinnovations.yayaagentic.startflow.StartFlowAdminService;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * Operator-facing Session Start Flow CRUD + publish lifecycle
 * ({@code docs/design/session-start-flows.md} §14). VIEWER may read; ADMIN may
 * create / version / publish. Bind a flow to a channel via the sender / chat
 * entry point editors (their {@code startFlowId} field), or to a profile via
 * {@code defaultStartFlowId}.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/start-flows")
public class StartFlowAdminController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final StartFlowAdminService service;

    public StartFlowAdminController(TenantGuard tenantGuard,
                                    WorkspaceAccessHelper workspaceAccess,
                                    StartFlowAdminService service) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.service = service;
    }

    @GetMapping
    public List<StartFlowDtos.FlowResponse> list(@PathVariable("tenantId") String tenantId,
                                                 ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return service.list(new Ids.TenantId(tenantId)).stream()
                .map(StartFlowDtos.FlowResponse::of).toList();
    }

    @GetMapping("/{id}")
    public StartFlowDtos.FlowResponse get(@PathVariable("tenantId") String tenantId,
                                          @PathVariable("id") String id,
                                          @RequestParam(value = "version", required = false) Integer version,
                                          ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return map(() -> StartFlowDtos.FlowResponse.of(service.get(new Ids.TenantId(tenantId), id, version)));
    }

    @PostMapping
    public ResponseEntity<StartFlowDtos.FlowResponse> create(@PathVariable("tenantId") String tenantId,
                                                             @RequestBody StartFlowDtos.CreateRequest req,
                                                             ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        if (req == null) throw AdminApiException.badRequest("missing_body", "request body required");
        var cmd = new StartFlowAdminService.CreateCommand(
                req.id(), req.name(), req.description(), req.steps(), req.greeting(),
                req.policy() == null ? FlowPolicy.defaults() : req.policy(),
                Boolean.TRUE.equals(req.publish()));
        var created = map(() -> service.create(new Ids.TenantId(tenantId), cmd));
        return ResponseEntity.status(HttpStatus.CREATED).body(StartFlowDtos.FlowResponse.of(created));
    }

    @PutMapping("/{id}")
    public StartFlowDtos.FlowResponse update(@PathVariable("tenantId") String tenantId,
                                             @PathVariable("id") String id,
                                             @RequestBody StartFlowDtos.UpdateRequest req,
                                             ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        if (req == null) throw AdminApiException.badRequest("missing_body", "request body required");
        var cmd = new StartFlowAdminService.UpdateCommand(
                req.name(), req.description(), req.steps(), req.greeting(), req.policy());
        return StartFlowDtos.FlowResponse.of(
                map(() -> service.update(new Ids.TenantId(tenantId), id, cmd)));
    }

    @PostMapping("/{id}/publish")
    public StartFlowDtos.FlowResponse publish(@PathVariable("tenantId") String tenantId,
                                              @PathVariable("id") String id,
                                              ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return StartFlowDtos.FlowResponse.of(map(() -> service.publish(new Ids.TenantId(tenantId), id)));
    }

    /** Translate the service's typed failures into the admin API's error shape. */
    private static <T> T map(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (StartFlowAdminService.NotFound e) {
            throw AdminApiException.notFound("start_flow_not_found", e.getMessage());
        } catch (StartFlowAdminService.Conflict e) {
            throw AdminApiException.conflict("start_flow_conflict", e.getMessage());
        } catch (StartFlowAdminService.Invalid e) {
            throw AdminApiException.badRequest("start_flow_invalid", e.getMessage());
        }
    }
}
