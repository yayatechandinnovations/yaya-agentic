package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.auth.stepup.TenantSecondaryComms;
import com.yayatechandinnovations.yayaagentic.auth.stepup.TenantSecondaryCommsService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.Optional;

/**
 * CRUD for tenants.secondary_comms_json — operators configure where step-up
 * OTPs are delivered.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §9.5.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/secondary-comms")
public class TenantSecondaryCommsController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final TenantSecondaryCommsService service;

    public TenantSecondaryCommsController(TenantGuard tenantGuard,
                                          WorkspaceAccessHelper workspaceAccess,
                                          TenantSecondaryCommsService service) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.service = service;
    }

    @GetMapping
    public SecondaryCommsResponse get(@PathVariable("tenantId") String tenantId,
                                      ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return SecondaryCommsResponse.of(service.forTenant(new Ids.TenantId(tenantId)));
    }

    @PutMapping
    public ResponseEntity<SecondaryCommsResponse> update(
            @PathVariable("tenantId") String tenantId,
            @RequestBody SecondaryCommsRequest body,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        if (body == null) {
            throw AdminApiException.badRequest("missing_body", "request body required");
        }
        TenantSecondaryComms comms = toComms(body);
        service.update(new Ids.TenantId(tenantId), comms);
        return ResponseEntity.ok(SecondaryCommsResponse.of(comms));
    }

    private static TenantSecondaryComms toComms(SecondaryCommsRequest req) {
        Optional<TenantSecondaryComms.EmailRelay> emailRelay = Optional.empty();
        if (req.emailRelay() != null) {
            String from = req.emailRelay().fromAddress();
            if (from == null || from.isBlank()) {
                throw AdminApiException.badRequest("missing_email_from",
                        "emailRelay.fromAddress is required when emailRelay is supplied");
            }
            if (!from.contains("@")) {
                throw AdminApiException.badRequest("invalid_email_from",
                        "emailRelay.fromAddress must look like an email");
            }
            emailRelay = Optional.of(new TenantSecondaryComms.EmailRelay(
                    from, req.emailRelay().providerId()));
        }
        Optional<TenantSecondaryComms.SmsBackup> smsBackup = Optional.empty();
        if (req.smsBackup() != null) {
            String fromNumber = req.smsBackup().fromNumber();
            if (fromNumber == null || !fromNumber.startsWith("+")) {
                throw AdminApiException.badRequest("invalid_sms_from",
                        "smsBackup.fromNumber must be E.164 (start with +)");
            }
            smsBackup = Optional.of(new TenantSecondaryComms.SmsBackup(
                    fromNumber, req.smsBackup().providerId()));
        }
        return new TenantSecondaryComms(emailRelay, smsBackup);
    }

    public record EmailRelayDto(String fromAddress, String providerId) {}
    public record SmsBackupDto(String fromNumber, String providerId) {}

    public record SecondaryCommsRequest(EmailRelayDto emailRelay,
                                        SmsBackupDto smsBackup) {}

    public record SecondaryCommsResponse(EmailRelayDto emailRelay,
                                         SmsBackupDto smsBackup) {
        static SecondaryCommsResponse of(TenantSecondaryComms comms) {
            return new SecondaryCommsResponse(
                    comms.emailRelay()
                            .map(e -> new EmailRelayDto(e.fromAddress(), e.providerId()))
                            .orElse(null),
                    comms.smsBackup()
                            .map(s -> new SmsBackupDto(s.fromNumber(), s.providerId()))
                            .orElse(null));
        }
    }
}
