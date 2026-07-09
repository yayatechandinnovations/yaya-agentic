package com.yayatechandinnovations.yayaidentity.api;

import com.yayatechandinnovations.yayaidentity.platform.audit.AuditLogEntity;
import com.yayatechandinnovations.yayaidentity.platform.audit.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Read the append-only audit log for an org (design §9.3, US-66). Operator view,
 * so both denial reasons are exposed. Token-protected by the resource-server chain.
 */
@RestController
@RequestMapping("/v1/identity/admin/orgs/{org}/audit")
public class AuditController {

    private final AuditLogRepository audit;

    public AuditController(AuditLogRepository audit) {
        this.audit = audit;
    }

    public record AuditRowDto(String eventType, String subject, String action, String resource,
                              String decision, String userReason, String auditReason, Instant at) {
        static AuditRowDto of(AuditLogEntity e) {
            return new AuditRowDto(e.getEventType(), e.getSubject(), e.getAction(), e.getResource(),
                    e.getDecision(), e.getUserReason(), e.getAuditReason(), e.getAt());
        }
    }

    @GetMapping
    public List<AuditRowDto> read(@PathVariable String org,
                                  @RequestParam(defaultValue = "50") int limit) {
        return audit.findByOrgIdOrderByAtDesc(org, PageRequest.of(0, Math.min(limit, 500)))
                .stream().map(AuditRowDto::of).toList();
    }
}
