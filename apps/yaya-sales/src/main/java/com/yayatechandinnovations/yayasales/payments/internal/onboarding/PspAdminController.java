package com.yayatechandinnovations.yayasales.payments.internal.onboarding;

import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantOnboarding.OnboardingSession;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin surface for in-app merchant PSP onboarding — the "Connect PayPal/Stripe"
 * button (design §10.1). Store-scoped like the rest of {@code /v1/sales/admin/*}.
 * Platform credentials never leave the server; the caller only ever receives
 * the hosted action URL. The console supplies the browser-facing return URL
 * itself (it knows its own origin; the server behind a proxy does not).
 */
@RestController
@RequestMapping("/v1/sales/admin/psp")
public class PspAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";
    /** The PSPs the console shows a card for; dLocal has no self-serve onboarding. */
    private static final List<String> KNOWN_PROVIDERS = List.of("stripe", "paypal", "dlocal");

    private final MerchantAccounts accounts;

    public PspAdminController(MerchantAccounts accounts) {
        this.accounts = accounts;
    }

    /** Connection status per known provider, for the badges / polling. */
    @GetMapping
    public List<MerchantAccountView> status(@RequestHeader(STORE_HEADER) String storeId) {
        StoreId store = new StoreId(storeId);
        return KNOWN_PROVIDERS.stream().map(p -> accounts.view(store, p)).toList();
    }

    /** Begin onboarding — returns the hosted URL the UI opens in a popup/tab. */
    @PostMapping("/{provider}/onboarding-link")
    public OnboardingLinkResponse onboardingLink(@RequestHeader(STORE_HEADER) String storeId,
                                                 @PathVariable String provider,
                                                 @Valid @RequestBody OnboardingLink body) {
        requireSupported(provider);
        String refreshUrl = body.refreshUrl() == null || body.refreshUrl().isBlank()
                ? body.returnUrl() : body.refreshUrl();
        OnboardingSession session = accounts.begin(
                new StoreId(storeId), provider, body.returnUrl(), refreshUrl);
        return new OnboardingLinkResponse(session.actionUrl(), session.trackingRef());
    }

    /**
     * The PSP returned the merchant to the console, which posts the redirect's
     * query params here to reconcile the connected account. The console keeps
     * polling {@code GET /v1/sales/admin/psp} for the ACTIVE badge afterwards.
     */
    @PostMapping("/{provider}/return")
    public MerchantAccountView onboardingReturn(@RequestHeader(STORE_HEADER) String storeId,
                                                @PathVariable String provider,
                                                @RequestBody ReturnCallback body) {
        requireSupported(provider);
        Map<String, Object> data = new HashMap<>(body.params() == null ? Map.of() : body.params());
        return accounts.reconcileReturn(new StoreId(storeId), provider, data);
    }

    /** Disconnect the connected account (soft — kept for audit; routing stops using it). */
    @DeleteMapping("/{provider}")
    public MerchantAccountView disconnect(@RequestHeader(STORE_HEADER) String storeId,
                                          @PathVariable String provider) {
        requireSupported(provider);
        return accounts.disconnect(new StoreId(storeId), provider);
    }

    private void requireSupported(String provider) {
        if (!accounts.supports(provider)) {
            throw new ApiErrors.NotFoundException("self-serve PSP onboarding for provider", provider);
        }
    }

    public record OnboardingLink(@NotBlank String returnUrl, String refreshUrl) {}

    public record ReturnCallback(Map<String, Object> params) {}

    public record OnboardingLinkResponse(String actionUrl, String trackingRef) {}
}
