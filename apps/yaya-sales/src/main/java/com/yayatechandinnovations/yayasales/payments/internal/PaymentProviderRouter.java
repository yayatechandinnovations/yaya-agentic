package com.yayatechandinnovations.yayasales.payments.internal;

import com.yayatechandinnovations.yayasales.payments.PaymentProvider;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantAccounts;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Picks the {@link PaymentProvider} for a store (design §8.3): a store with
 * exactly one connected (ACTIVE + charges-enabled) PSP account routes to that
 * provider — connecting PayPal IS choosing PayPal — otherwise the configured
 * default applies ({@code yaya.sales.payments.default-provider}, "sandbox" so
 * dev/test never needs a PSP). Ambiguity (several connected) also falls back to
 * the default rather than guessing with money.
 */
@Component
public class PaymentProviderRouter {

    private final Map<String, PaymentProvider> providers;
    private final MerchantAccounts merchantAccounts;
    private final String defaultProvider;

    public PaymentProviderRouter(List<PaymentProvider> providers,
                                 MerchantAccounts merchantAccounts,
                                 @Value("${yaya.sales.payments.default-provider:sandbox}")
                                 String defaultProvider) {
        this.providers = providers.stream()
                .collect(Collectors.toMap(p -> p.name().toLowerCase(Locale.ROOT), p -> p));
        this.merchantAccounts = merchantAccounts;
        this.defaultProvider = defaultProvider;
    }

    public PaymentProvider resolve(StoreId store) {
        return merchantAccounts.anyConnected(store)
                .map(connected -> providers.get(connected.provider().toLowerCase(Locale.ROOT)))
                .filter(java.util.Objects::nonNull)
                .orElseGet(this::fallback);
    }

    private PaymentProvider fallback() {
        PaymentProvider provider = providers.get(defaultProvider.toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new IllegalStateException("no PaymentProvider named '" + defaultProvider + "'");
        }
        return provider;
    }
}
