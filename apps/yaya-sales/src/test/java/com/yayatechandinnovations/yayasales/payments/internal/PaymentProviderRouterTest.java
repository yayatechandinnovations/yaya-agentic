package com.yayatechandinnovations.yayasales.payments.internal;

import com.yayatechandinnovations.yayasales.payments.PaymentProvider;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantAccounts;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * §8.3 — routing: connecting a PSP account IS choosing that provider for the
 * store; everything else (no connection, ambiguity, a connected provider with
 * no adapter) falls back to the configured default so dev/test stays on sandbox.
 */
class PaymentProviderRouterTest {

    private final MerchantAccounts accounts = Mockito.mock(MerchantAccounts.class);
    private final StoreId store = new StoreId("store-1");

    private static PaymentProvider named(String name) {
        return new PaymentProvider() {
            @Override public String name() { return name; }
            @Override public AuthorizeOutcome authorize(AuthorizeRequest request) {
                return new AuthorizeOutcome.Failed("unused");
            }
            @Override public RefundOutcome refund(RefundRequest request) {
                return new RefundOutcome.Failed("unused");
            }
        };
    }

    @Test
    void storeWithAConnectedStripeAccountRoutesToStripe() {
        when(accounts.anyConnected(store)).thenReturn(Optional.of(
                new MerchantAccounts.ConnectedAccount(store, "stripe", "acct_1")));
        PaymentProviderRouter router = new PaymentProviderRouter(
                List.of(named("sandbox"), named("stripe")), accounts, "sandbox");

        assertThat(router.resolve(store).name()).isEqualTo("stripe");
    }

    @Test
    void storeWithNoConnectedAccountFallsBackToTheDefaultProvider() {
        when(accounts.anyConnected(store)).thenReturn(Optional.empty());
        PaymentProviderRouter router = new PaymentProviderRouter(
                List.of(named("sandbox"), named("stripe")), accounts, "sandbox");

        assertThat(router.resolve(store).name()).isEqualTo("sandbox");
    }

    @Test
    void connectedProviderWithoutAnAdapterBeanFallsBackToTheDefault() {
        when(accounts.anyConnected(store)).thenReturn(Optional.of(
                new MerchantAccounts.ConnectedAccount(store, "dlocal", "sub-1")));
        PaymentProviderRouter router = new PaymentProviderRouter(
                List.of(named("sandbox"), named("stripe")), accounts, "sandbox");

        assertThat(router.resolve(store).name()).isEqualTo("sandbox");
    }
}
