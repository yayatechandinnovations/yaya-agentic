package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppEmbeddedSignupDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.BusinessProfile;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.EmbeddedSignupProperties;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.WhatsAppEmbeddedSignupService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.WhatsAppMessagingAccountService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;

/**
 * Self-service "Connect WhatsApp" surface: Meta Embedded Signup config for
 * the wizard, the onboarding submission endpoint, attempt status reads, and
 * messaging-account listing/disconnect. Same authorization skeleton as every
 * tenant admin controller ({@code workspaceAccess.require} +
 * {@code tenantGuard}); the tenantId in the path is validated against the
 * authenticated operator's workspace membership — a browser-supplied id
 * grants nothing by itself.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/whatsapp")
public class WhatsAppEmbeddedSignupController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final WhatsAppEmbeddedSignupService signup;
    private final WhatsAppMessagingAccountService accounts;
    private final EmbeddedSignupProperties props;

    public WhatsAppEmbeddedSignupController(TenantGuard tenantGuard,
                                            WorkspaceAccessHelper workspaceAccess,
                                            WhatsAppEmbeddedSignupService signup,
                                            WhatsAppMessagingAccountService accounts,
                                            EmbeddedSignupProperties props) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.signup = signup;
        this.accounts = accounts;
        this.props = props;
    }

    @GetMapping("/embedded-signup/config")
    public WhatsAppEmbeddedSignupDtos.SignupConfigResponse config(
            @PathVariable("tenantId") String tenantId,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        boolean enabled = props.enabled() && props.isConfigured();
        return new WhatsAppEmbeddedSignupDtos.SignupConfigResponse(
                enabled,
                enabled ? props.metaAppId() : null,
                enabled ? props.metaConfigurationId() : null,
                enabled ? props.partnerSolutionId() : null);
    }

    @PostMapping("/embedded-signup")
    public Mono<ResponseEntity<WhatsAppEmbeddedSignupDtos.OnboardingResponse>> start(
            @PathVariable("tenantId") String tenantId,
            @RequestBody WhatsAppEmbeddedSignupDtos.OnboardingRequestBody body,
            ServerWebExchange exchange) {
        // Body-carrying handlers are invoked on the event loop; the
        // orchestration makes blocking Twilio calls, so offload to
        // boundedElastic (same idiom as UserAccountsController).
        return Mono.fromCallable(() -> {
            workspaceAccess.require(tenantId, exchange, Role.ADMIN);
            tenantGuard.requireWritable(tenantId);
            validateBody(body);

            var result = signup.startOnboarding(new Ids.TenantId(tenantId),
                    new WhatsAppEmbeddedSignupService.OnboardingRequest(
                            body.phoneE164().trim(),
                            body.wabaId().trim(),
                            body.metaPhoneNumberId() == null ? null : body.metaPhoneNumberId().trim(),
                            body.displayName().trim(),
                            body.businessProfile().toDomain(),
                            Optional.ofNullable(body.defaultLocale())));
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new WhatsAppEmbeddedSignupDtos.OnboardingResponse(
                            WhatsAppEmbeddedSignupDtos.AttemptView.of(result.attempt()),
                            result.sender() == null ? null
                                    : WhatsAppSenderDtos.SenderResponse.of(result.sender())));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/embedded-signup/attempts")
    public List<WhatsAppEmbeddedSignupDtos.AttemptView> attempts(
            @PathVariable("tenantId") String tenantId,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return signup.listForTenant(new Ids.TenantId(tenantId)).stream()
                .map(WhatsAppEmbeddedSignupDtos.AttemptView::of)
                .toList();
    }

    @GetMapping("/embedded-signup/attempts/{attemptId}")
    public WhatsAppEmbeddedSignupDtos.AttemptView attempt(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("attemptId") String attemptId,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return WhatsAppEmbeddedSignupDtos.AttemptView.of(
                signup.requireForTenant(new Ids.TenantId(tenantId), attemptId));
    }

    @GetMapping("/messaging-accounts")
    public List<WhatsAppEmbeddedSignupDtos.MessagingAccountView> messagingAccounts(
            @PathVariable("tenantId") String tenantId,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return accounts.listForTenant(new Ids.TenantId(tenantId)).stream()
                .map(WhatsAppEmbeddedSignupDtos.MessagingAccountView::of)
                .toList();
    }

    @PostMapping("/messaging-accounts/{accountId}/disconnect")
    public WhatsAppEmbeddedSignupDtos.MessagingAccountView disconnect(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("accountId") String accountId,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return WhatsAppEmbeddedSignupDtos.MessagingAccountView.of(
                accounts.disconnect(new Ids.TenantId(tenantId), accountId));
    }

    /* ---------------------------- helpers --------------------------------- */

    private static void validateBody(WhatsAppEmbeddedSignupDtos.OnboardingRequestBody body) {
        if (body == null) {
            throw AdminApiException.badRequest("missing_body", "request body required");
        }
        if (isBlank(body.phoneE164())) {
            throw AdminApiException.badRequest("bad_phone", "phoneE164 must not be blank");
        }
        if (isBlank(body.wabaId())) {
            throw AdminApiException.badRequest("bad_waba_id", "wabaId must not be blank");
        }
        if (isBlank(body.displayName())) {
            throw AdminApiException.badRequest("bad_display_name", "displayName must not be blank");
        }
        if (body.businessProfile() == null) {
            throw AdminApiException.badRequest("missing_business_profile", "businessProfile required");
        }
        if (!BusinessProfile.isValidWebsiteUrl(body.businessProfile().websiteUrl())) {
            throw AdminApiException.badRequest("bad_business_profile",
                    "websiteUrl must be a web address such as https://example.com");
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
