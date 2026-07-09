package com.yayatechandinnovations.yayasales.payments.internal.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantOnboarding.OnboardingCallback;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantOnboarding.OnboardingSession;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantOnboarding.OnboardingStart;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantOnboarding.ReconcileResult;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates in-app merchant PSP onboarding: begins the hosted flow, reconciles
 * the return/webhook into a persisted {@code merchant_psp_accounts} row, and
 * resolves the connected account id for charging. Provider-specific work lives in
 * the {@link MerchantOnboarding} beans; this owns persistence, tracking-ref
 * correlation, and the store/provider status. Design §8.3, §10.1.
 */
@Service
public class MerchantAccounts {

    private static final Logger log = LoggerFactory.getLogger(MerchantAccounts.class);

    private final Map<String, MerchantOnboarding> providers;
    private final MerchantAccountRepository repo;
    private final ObjectMapper json;

    public MerchantAccounts(List<MerchantOnboarding> onboarders,
                            MerchantAccountRepository repo,
                            ObjectMapper json) {
        this.providers = onboarders.stream().collect(
                java.util.stream.Collectors.toMap(o -> o.provider().toLowerCase(Locale.ROOT), o -> o));
        this.repo = repo;
        this.json = json;
    }

    /** Whether a provider supports self-serve onboarding (has a bean) — dLocal doesn't. */
    public boolean supports(String provider) {
        return providers.containsKey(norm(provider));
    }

    /**
     * Start onboarding: mint a tracking ref, move the row to ONBOARDING, and ask
     * the provider for the hosted URL. {@code returnUrl} is the absolute URL the
     * PSP sends the merchant back to.
     */
    @Transactional
    public OnboardingSession begin(StoreId store, String provider, String returnUrl, String refreshUrl) {
        MerchantOnboarding onboarding = require(provider);
        String trackingRef = "onb_" + UUID.randomUUID().toString().replace("-", "");

        MerchantAccountEntity row = load(store, onboarding.provider());
        row.setTrackingRef(trackingRef);
        row.setStatus(MerchantAccountStatus.ONBOARDING.name());
        row.setChargesEnabled(false);

        OnboardingSession session = onboarding.start(
                new OnboardingStart(store, trackingRef, returnUrl, refreshUrl));
        // Some providers (Stripe) mint the connected account up front — persist it
        // now so the return/webhook can correlate by account id.
        if (session.accountId() != null && !session.accountId().isBlank()) {
            row.setAccountId(session.accountId());
        }
        repo.save(row);
        return session;
    }

    /** Reconcile a return redirect (params) for a known store+provider. */
    @Transactional
    public MerchantAccountView reconcileReturn(StoreId store, String provider, Map<String, Object> params) {
        MerchantOnboarding onboarding = require(provider);
        MerchantAccountEntity row = load(store, onboarding.provider());
        return apply(row, onboarding.provider(),
                onboarding.reconcile(new OnboardingCallback(store, row.getTrackingRef(), enrich(params, row))));
    }

    /**
     * Reconcile an async PSP webhook. The webhook carries our tracking ref (not the
     * store), so resolve the row by it. Returns empty for an unknown ref.
     */
    @Transactional
    public Optional<MerchantAccountView> reconcileWebhook(String trackingRef, Map<String, Object> payload) {
        if (trackingRef == null || trackingRef.isBlank()) return Optional.empty();
        return repo.findByTrackingRef(trackingRef).map(row -> reconcileRow(row, payload));
    }

    /**
     * Reconcile a webhook that correlates by connected-account id rather than our
     * tracking ref (Stripe's {@code account.updated} carries the {@code acct_…}, not
     * our id). Returns empty for an unknown account.
     */
    @Transactional
    public Optional<MerchantAccountView> reconcileWebhookByAccount(String provider, String accountId,
                                                                   Map<String, Object> payload) {
        if (accountId == null || accountId.isBlank()) return Optional.empty();
        return repo.findByProviderAndAccountId(norm(provider), accountId)
                .map(row -> reconcileRow(row, enrich(payload, row)));
    }

    private MerchantAccountView reconcileRow(MerchantAccountEntity row, Map<String, Object> payload) {
        MerchantOnboarding onboarding = providers.get(norm(row.getProvider()));
        if (onboarding == null) return null;
        return apply(row, row.getProvider(), onboarding.reconcile(
                new OnboardingCallback(new StoreId(row.getStoreId()), row.getTrackingRef(), payload)));
    }

    /** Fold the row's known account id into the callback data so a provider that
     *  correlates by account (Stripe on return) can query its status. */
    private static Map<String, Object> enrich(Map<String, Object> data, MerchantAccountEntity row) {
        Map<String, Object> out = new java.util.HashMap<>(data == null ? Map.of() : data);
        if (row.getAccountId() != null && !row.getAccountId().isBlank()) {
            out.putIfAbsent("accountId", row.getAccountId());
        }
        return out;
    }

    @Transactional(readOnly = true)
    public MerchantAccountView view(StoreId store, String provider) {
        String p = norm(provider);
        if (!providers.containsKey(p)) return MerchantAccountView.unsupported(p);
        return repo.findByStoreIdAndProvider(store.value(), p)
                .map(this::toView)
                .orElse(new MerchantAccountView(p, MerchantAccountStatus.PENDING, false, null, true));
    }

    /** Disconnect (soft) — the row stays for audit; the router stops routing to it. */
    @Transactional
    public MerchantAccountView disconnect(StoreId store, String provider) {
        MerchantOnboarding onboarding = require(provider);
        MerchantAccountEntity row = load(store, onboarding.provider());
        row.setStatus(MerchantAccountStatus.DISABLED.name());
        row.setChargesEnabled(false);
        return toView(repo.save(row));
    }

    /**
     * The connected account id to charge for {@code (store, provider)} — only when
     * ACTIVE and charges-enabled. Empty ⇒ the provider makes a platform charge.
     * Called by the PSP providers at authorize time (design §8.3).
     */
    @Transactional(readOnly = true)
    public Optional<String> connectedAccountId(StoreId store, String provider) {
        return repo.findByStoreIdAndProvider(store.value(), norm(provider))
                .filter(MerchantAccounts::chargeable)
                .map(MerchantAccountEntity::getAccountId)
                .filter(id -> id != null && !id.isBlank());
    }

    /**
     * The store's single connected PSP account, when exactly one exists — the
     * router's "this store clearly wants that provider" signal. Zero or several
     * connected providers ⇒ empty, and routing falls back to the configured default.
     */
    @Transactional(readOnly = true)
    public Optional<ConnectedAccount> anyConnected(StoreId store) {
        List<MerchantAccountEntity> connected = repo.findByStoreId(store.value()).stream()
                .filter(MerchantAccounts::chargeable)
                .filter(r -> r.getAccountId() != null && !r.getAccountId().isBlank())
                .toList();
        if (connected.size() != 1) return Optional.empty();
        MerchantAccountEntity row = connected.get(0);
        return Optional.of(new ConnectedAccount(store, row.getProvider(), row.getAccountId()));
    }

    /** The store owning a (provider, connected-account id) — webhook idempotency scoping. */
    @Transactional(readOnly = true)
    public Optional<StoreId> storeByAccount(String provider, String accountId) {
        if (accountId == null || accountId.isBlank()) return Optional.empty();
        return repo.findByProviderAndAccountId(norm(provider), accountId)
                .map(r -> new StoreId(r.getStoreId()));
    }

    /** The store owning a tracking ref — webhook idempotency scoping. */
    @Transactional(readOnly = true)
    public Optional<StoreId> storeByTrackingRef(String trackingRef) {
        if (trackingRef == null || trackingRef.isBlank()) return Optional.empty();
        return repo.findByTrackingRef(trackingRef).map(r -> new StoreId(r.getStoreId()));
    }

    public record ConnectedAccount(StoreId store, String provider, String accountId) {}

    // ---- internals ---------------------------------------------------------

    private static boolean chargeable(MerchantAccountEntity r) {
        return MerchantAccountStatus.ACTIVE.name().equals(r.getStatus()) && r.isChargesEnabled();
    }

    private MerchantAccountView apply(MerchantAccountEntity row, String provider, ReconcileResult result) {
        if (result.accountId() != null && !result.accountId().isBlank()) {
            row.setAccountId(result.accountId());
        }
        row.setStatus(result.status().name());
        row.setChargesEnabled(result.chargesEnabled());
        row.setDetailJson(writeJson(result.detail()));
        MerchantAccountView view = toView(repo.save(row));
        log.info("PSP onboarding reconciled: store={} provider={} status={} chargesEnabled={}",
                row.getStoreId(), provider, view.status(), view.chargesEnabled());
        return view;
    }

    private MerchantAccountEntity load(StoreId store, String provider) {
        return repo.findByStoreIdAndProvider(store.value(), norm(provider))
                .orElseGet(() -> new MerchantAccountEntity(store.value(), norm(provider)));
    }

    private MerchantAccountView toView(MerchantAccountEntity e) {
        return new MerchantAccountView(
                e.getProvider(),
                MerchantAccountStatus.valueOf(e.getStatus()),
                e.isChargesEnabled(),
                e.getAccountId(),
                true);
    }

    private MerchantOnboarding require(String provider) {
        MerchantOnboarding o = providers.get(norm(provider));
        if (o == null) throw new IllegalArgumentException("provider does not support onboarding: " + provider);
        return o;
    }

    private static String norm(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private String writeJson(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
