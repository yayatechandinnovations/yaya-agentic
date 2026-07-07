package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding.MerchantAccountView;
import com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding.MerchantOnboarding.OnboardingSession;
import com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding.MerchantPspAccountService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin surface for in-app merchant PSP onboarding — the "Connect PayPal/Stripe"
 * button. Workspace-scoped like the rest of {@code /v1/admin/tenants/{id}/…}.
 * Platform credentials never leave the server; the browser only ever receives
 * the hosted action URL. See {@code merchant-psp-onboarding-design.md} §7.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/commerce/psp/{provider}")
public class MerchantPspOnboardingController {

    private final MerchantPspAccountService accounts;
    private final WorkspaceAccessHelper workspaceAccess;
    private final TenantGuard tenantGuard;

    public MerchantPspOnboardingController(MerchantPspAccountService accounts,
                                           WorkspaceAccessHelper workspaceAccess,
                                           TenantGuard tenantGuard) {
        this.accounts = accounts;
        this.workspaceAccess = workspaceAccess;
        this.tenantGuard = tenantGuard;
    }

    /** Current connection status for the badge / polling. */
    @GetMapping
    public MerchantAccountView status(@PathVariable("tenantId") String tenantId,
                                      @PathVariable("provider") String provider,
                                      ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return accounts.view(new Ids.TenantId(tenantId), provider);
    }

    /** Begin onboarding — returns the hosted URL the UI opens in a popup/tab. */
    @PostMapping("/onboarding-link")
    public OnboardingLinkResponse onboardingLink(@PathVariable("tenantId") String tenantId,
                                                 @PathVariable("provider") String provider,
                                                 ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        if (!accounts.supports(provider)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "provider does not support self-serve onboarding: " + provider);
        }
        // The PSP redirects the merchant back to OUR return endpoint (derived from
        // this request's origin), where we reconcile the connected account.
        String returnUrl = UriComponentsBuilder.fromUri(exchange.getRequest().getURI())
                .replacePath("/v1/admin/tenants/" + tenantId + "/commerce/psp/" + provider + "/return")
                .replaceQuery(null)
                .build().toUriString();
        OnboardingSession session = accounts.begin(new Ids.TenantId(tenantId), provider, returnUrl, returnUrl);
        return new OnboardingLinkResponse(session.actionUrl(), session.trackingRef());
    }

    /**
     * Where the PSP returns the merchant after the hosted flow. Reconciles the
     * connected account, then shows a tiny "you can close this window" page — the
     * console UI is polling {@code GET …/psp/{provider}} for the ACTIVE status.
     */
    @GetMapping("/return")
    public ResponseEntity<String> onboardingReturn(@PathVariable("tenantId") String tenantId,
                                                   @PathVariable("provider") String provider,
                                                   @RequestParam Map<String, String> params,
                                                   ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        Map<String, Object> data = new HashMap<>(params);
        MerchantAccountView view = accounts.reconcileReturn(new Ids.TenantId(tenantId), provider, data);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(returnPage(provider, view));
    }

    /** Disconnect the connected account (soft — kept for audit; checkout stops using it). */
    @DeleteMapping
    public MerchantAccountView disconnect(@PathVariable("tenantId") String tenantId,
                                          @PathVariable("provider") String provider,
                                          ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        if (!accounts.supports(provider)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "provider does not support self-serve onboarding: " + provider);
        }
        return accounts.disconnect(new Ids.TenantId(tenantId), provider);
    }

    private static String returnPage(String provider, MerchantAccountView view) {
        String headline = view.connected()
                ? "You're connected ✓"
                : "Almost there…";
        String detail = view.connected()
                ? "Your " + provider + " account is connected. You can close this window."
                : "We're finishing connecting your " + provider + " account. You can close this "
                        + "window; the app will update automatically.";
        // Best-effort auto-close for the popup case; harmless as a top-level page.
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Connect " + provider
                + "</title></head><body style=\"font-family:system-ui;padding:2rem;text-align:center\">"
                + "<h2>" + headline + "</h2><p>" + detail + "</p>"
                + "<script>try{window.opener&&window.opener.postMessage("
                + "{type:'psp-onboarding',provider:'" + provider + "',connected:" + view.connected()
                + "},'*');setTimeout(function(){window.close();},1200);}catch(e){}</script>"
                + "</body></html>";
    }

    public record OnboardingLinkResponse(String actionUrl, String trackingRef) {}
}
