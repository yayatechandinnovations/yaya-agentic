package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.SystemSenderDtos.SenderRequest;
import com.yayatechandinnovations.yayaagentic.api.dto.SystemSenderDtos.SenderResponse;
import com.yayatechandinnovations.yayaagentic.channel.system.Correlation;
import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.channel.system.SenderTrust;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemInboundMessage;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService.Command;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.outreach.OutreachService;
import com.yayatechandinnovations.yayaagentic.persistence.SystemInboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SystemInboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin CRUD for a tenant's integration senders — the "system inbound" block on
 * a chat entry point ({@code system-messaging-design.md} §3, §14). Enabling a
 * sender opens the ingest edge ({@code POST /v1/integrations/…}); the trust +
 * addressing config here bounds who may inject and at whom.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/system-senders")
public class SystemSenderController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final SystemSenderService service;
    private final SystemInboundMessageRepository ledger;
    private final OutreachService outreach;

    public SystemSenderController(TenantGuard tenantGuard,
                                  WorkspaceAccessHelper workspaceAccess,
                                  SystemSenderService service,
                                  SystemInboundMessageRepository ledger,
                                  OutreachService outreach) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.service = service;
        this.ledger = ledger;
        this.outreach = outreach;
    }

    @GetMapping
    public List<SenderResponse> list(@PathVariable("tenantId") String tenantId,
                                     ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        return service.listForTenant(new TenantId(tenantId)).stream()
                .map(SenderResponse::of).toList();
    }

    @GetMapping("/{senderId}")
    public SenderResponse get(@PathVariable("tenantId") String tenantId,
                              @PathVariable("senderId") String senderId,
                              ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        return SenderResponse.of(
                service.requireForTenant(new TenantId(tenantId), new Ids.SystemSenderId(senderId)));
    }

    @PostMapping
    public ResponseEntity<SenderResponse> create(@PathVariable("tenantId") String tenantId,
                                                 @RequestBody SenderRequest req,
                                                 ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        var created = service.create(new TenantId(tenantId), toCommand(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(SenderResponse.of(created));
    }

    @PutMapping("/{senderId}")
    public SenderResponse update(@PathVariable("tenantId") String tenantId,
                                 @PathVariable("senderId") String senderId,
                                 @RequestBody SenderRequest req,
                                 ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        var updated = service.update(new TenantId(tenantId),
                new Ids.SystemSenderId(senderId), toCommand(req));
        return SenderResponse.of(updated);
    }

    @DeleteMapping("/{senderId}")
    public ResponseEntity<Void> delete(@PathVariable("tenantId") String tenantId,
                                       @PathVariable("senderId") String senderId,
                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        service.delete(new TenantId(tenantId), new Ids.SystemSenderId(senderId));
        return ResponseEntity.noContent().build();
    }

    /* ------------------------- integration log (§14) ---------------------- */

    @GetMapping("/{senderId}/messages")
    public List<LogEntry> log(@PathVariable("tenantId") String tenantId,
                              @PathVariable("senderId") String senderId,
                              @RequestParam(value = "page", defaultValue = "0") int page,
                              @RequestParam(value = "size", defaultValue = "50") int size,
                              ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        return ledger.findByTenantIdAndSenderIdOrderByReceivedAtDesc(
                        tenantId, senderId, PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))))
                .map(LogEntry::of).getContent();
    }

    /* --------------------- playground: simulate a message ----------------- */

    /**
     * Exercise ingest → deliver end-to-end without a real integration (§14
     * playground): the operator posts as if the linked system did. Trust +
     * replay guard are skipped (this is an already-authenticated operator); the
     * message flows through the same {@link OutreachService} a production sender
     * hits. Each call uses a fresh dedup key, so it always delivers.
     */
    @PostMapping("/{senderId}/simulate")
    public OutreachService.OutreachReceipt simulate(@PathVariable("tenantId") String tenantId,
                                                    @PathVariable("senderId") String senderId,
                                                    @RequestBody SimulateRequest req,
                                                    ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        TenantId tenant = new TenantId(tenantId);
        OutreachRecipient recipient = new OutreachRecipient.ByPrincipal(
                req.subject() == null ? "" : req.subject());
        SystemInboundMessage msg = new SystemInboundMessage(
                tenant, new Ids.SystemSenderId(senderId), recipient,
                req.body(), List.of(),
                new Correlation(req.originatorName(), req.originatorRef() == null
                        ? "sim-" + UUID.randomUUID() : req.originatorRef(), req.subjectLine()),
                "sim-" + UUID.randomUUID(), Map.of("simulated", Boolean.TRUE));
        Principal operator = new Principal("playground:operator", tenant,
                Set.of(SenderTrust.SEND_SCOPE), Map.of(), Instant.now());
        return outreach.ingest(msg, new OutreachService.OutreachAuth(operator));
    }

    public record SimulateRequest(String subject, String body, String originatorName,
                                  String originatorRef, String subjectLine) {}

    public record LogEntry(String id, String status, String body, Instant receivedAt, String sessionId) {
        static LogEntry of(SystemInboundMessageEntity e) {
            return new LogEntry(e.getId(), e.getStatus(), e.getBody(),
                    e.getReceivedAt() == null ? Instant.now() : e.getReceivedAt().toInstant(),
                    e.getSessionId());
        }
    }

    private static Command toCommand(SenderRequest req) {
        return new Command(
                req.entryPoint(),
                req.displayName(),
                req.enabled(),
                req.trust(),
                req.mediationMode(),
                req.addressing(),
                req.responseToolId(),
                req.capabilityMask(),
                req.limits());
    }
}
