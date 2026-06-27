package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpAuditEntity;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpAuditRepository;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.List;

/**
 * Operator-facing read endpoint over {@code step_up_audit}. Powers the
 * "Step-up" tab on the session viewer.
 *
 * <p>{@code target_address} is already masked on disk (the coordinator stores
 * {@code a***@example.com} or {@code +1***1234}) — the endpoint returns the
 * stored form verbatim. No PII flag toggling here in v1.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.7.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/step-up-audit")
public class StepUpAuditController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final StepUpAuditRepository audit;

    public StepUpAuditController(TenantGuard tenantGuard,
                                 WorkspaceAccessHelper workspaceAccess,
                                 StepUpAuditRepository audit) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.audit = audit;
    }

    @GetMapping
    public List<StepUpAuditRow> list(@PathVariable("tenantId") String tenantId,
                                     @RequestParam(value = "session_id", required = false) String sessionId,
                                     ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        if (sessionId == null || sessionId.isBlank()) {
            throw AdminApiException.badRequest("missing_session_id",
                    "session_id query parameter is required");
        }
        return audit.findByTenantIdAndSessionIdOrderByStartedAtDesc(tenantId, sessionId)
                .stream()
                .map(StepUpAuditRow::of)
                .toList();
    }

    public record StepUpAuditRow(
            Long    id,
            String  sessionId,
            String  audience,
            String  method,
            String  initialSubject,
            String  elevatedSubject,
            String  outcome,
            Instant startedAt,
            Instant completedAt,
            String  targetAddress,
            String  correlationUlid
    ) {
        static StepUpAuditRow of(StepUpAuditEntity e) {
            return new StepUpAuditRow(
                    e.getId(),
                    e.getSessionId(),
                    e.getAudience(),
                    e.getMethod(),
                    e.getInitialSubject(),
                    e.getElevatedSubject(),
                    e.getOutcome(),
                    e.getStartedAt() == null ? null : e.getStartedAt().toInstant(),
                    e.getCompletedAt() == null ? null : e.getCompletedAt().toInstant(),
                    e.getTargetAddress(),
                    e.getCorrelationUlid());
        }
    }
}
