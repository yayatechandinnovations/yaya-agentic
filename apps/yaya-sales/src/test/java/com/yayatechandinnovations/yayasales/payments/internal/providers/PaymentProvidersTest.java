package com.yayatechandinnovations.yayasales.payments.internal.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.payments.PaymentProvider;
import com.yayatechandinnovations.yayasales.payments.PaymentProvider.AuthorizeOutcome;
import com.yayatechandinnovations.yayasales.payments.PaymentProvider.AuthorizeRequest;
import com.yayatechandinnovations.yayasales.payments.PaymentProvider.RefundOutcome;
import com.yayatechandinnovations.yayasales.payments.PaymentProvider.RefundRequest;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantAccounts;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §8.3 — the ported PSP adapters degrade gracefully: with no credentials each
 * fabricates a deterministic outcome keyed on OUR order id (the PSP order
 * reference the webhook echoes back), so the whole checkout path is exercisable
 * offline. Live-path coverage is deferred to real PSP sandbox accounts.
 */
class PaymentProvidersTest {

    private final ObjectMapper json = new ObjectMapper();
    private final MerchantAccounts accounts = Mockito.mock(MerchantAccounts.class);
    private final StoreId store = new StoreId("store-1");
    private final OrderId order = new OrderId("ord-42");
    private final Money amount = new Money(12_345, "DOP");

    private AuthorizeRequest authorize() {
        return new AuthorizeRequest(store, order, amount, "https://shop.example/return");
    }

    // ── dLocal ───────────────────────────────────────────────────────────────

    @Test
    void unconfiguredDLocalFabricatesAHostedUrlKeyedOnTheOrderId() {
        DLocalPaymentProvider dlocal = new DLocalPaymentProvider(
                new DLocalProperties(null, null, null, null, null), accounts, json);

        assertThat(dlocal.name()).isEqualTo("dlocal");
        assertThat(dlocal.isConfigured()).isFalse();

        AuthorizeOutcome outcome = dlocal.authorize(authorize());
        assertThat(outcome).isInstanceOfSatisfying(AuthorizeOutcome.HostedUrl.class, hosted -> {
            assertThat(hosted.url()).contains("ord-42").contains("amount=12345").contains("currency=DOP");
            assertThat(hosted.pspRef()).isEqualTo("ord-42");
        });
    }

    @Test
    void unconfiguredDLocalRefundsInSandbox() {
        DLocalPaymentProvider dlocal = new DLocalPaymentProvider(
                new DLocalProperties(null, null, null, null, null), accounts, json);

        RefundOutcome outcome = dlocal.refund(new RefundRequest(store, order, amount, "psp-1"));
        assertThat(outcome).isInstanceOfSatisfying(RefundOutcome.Refunded.class,
                r -> assertThat(r.pspRef()).isEqualTo("re_sandbox_psp-1"));
    }

    @Test
    void configuredDLocalRefundsAreOutOfBandInV1() {
        DLocalPaymentProvider dlocal = new DLocalPaymentProvider(
                new DLocalProperties(null, "login", "trans", "secret", null), accounts, json);

        RefundOutcome outcome = dlocal.refund(new RefundRequest(store, order, amount, "psp-1"));
        assertThat(outcome).isInstanceOfSatisfying(RefundOutcome.Failed.class,
                f -> assertThat(f.reason()).contains("out-of-band"));
    }

    // ── Stripe ───────────────────────────────────────────────────────────────

    @Test
    void unconfiguredStripeFabricatesAPaymentIntentWithTheSandboxKey() {
        StripeConnectPaymentProvider stripe = new StripeConnectPaymentProvider(
                new StripeProperties(null, null, null, null), accounts, json);

        assertThat(stripe.name()).isEqualTo("stripe");
        assertThat(stripe.isConfigured()).isFalse();

        AuthorizeOutcome outcome = stripe.authorize(authorize());
        assertThat(outcome).isInstanceOfSatisfying(AuthorizeOutcome.Intent.class, intent -> {
            assertThat(intent.publishableKey()).isEqualTo("pk_test_sandbox");
            assertThat(intent.clientSecret()).contains("ord-42");
            assertThat(intent.pspRef()).isEqualTo("pi_sandbox_ord-42");
        });
    }

    @Test
    void unconfiguredStripeRefundsInSandbox() {
        StripeConnectPaymentProvider stripe = new StripeConnectPaymentProvider(
                new StripeProperties(null, null, null, null), accounts, json);

        RefundOutcome outcome = stripe.refund(new RefundRequest(store, order, amount, "pi_1"));
        assertThat(outcome).isInstanceOfSatisfying(RefundOutcome.Refunded.class,
                r -> assertThat(r.pspRef()).isEqualTo("re_sandbox_pi_1"));
    }

    // ── PayPal ───────────────────────────────────────────────────────────────

    @Test
    void unconfiguredPayPalFabricatesAnApprovalUrlKeyedOnTheOrderId() {
        PayPalPaymentProvider paypal = new PayPalPaymentProvider(
                new PayPalProperties(null, null, null, null, null, null), accounts, json);

        assertThat(paypal.name()).isEqualTo("paypal");
        assertThat(paypal.isConfigured()).isFalse();

        AuthorizeOutcome outcome = paypal.authorize(authorize());
        assertThat(outcome).isInstanceOfSatisfying(AuthorizeOutcome.HostedUrl.class, hosted -> {
            assertThat(hosted.url()).contains("token=ord-42");
            assertThat(hosted.pspRef()).isEqualTo("ord-42");
        });
    }

    @Test
    void unconfiguredPayPalRefundsInSandboxAndConfiguredIsOutOfBand() {
        PayPalPaymentProvider sandbox = new PayPalPaymentProvider(
                new PayPalProperties(null, null, null, null, null, null), accounts, json);
        assertThat(sandbox.refund(new RefundRequest(store, order, amount, "cap-1")))
                .isInstanceOfSatisfying(RefundOutcome.Refunded.class,
                        r -> assertThat(r.pspRef()).isEqualTo("re_sandbox_cap-1"));

        PayPalPaymentProvider live = new PayPalPaymentProvider(
                new PayPalProperties("id", "secret", null, null, null, null), accounts, json);
        assertThat(live.refund(new RefundRequest(store, order, amount, "cap-1")))
                .isInstanceOfSatisfying(RefundOutcome.Failed.class,
                        f -> assertThat(f.reason()).contains("out-of-band"));
    }

    @Test
    void everyProviderKeepsItsRoutableName() {
        PaymentProvider dlocal = new DLocalPaymentProvider(
                new DLocalProperties(null, null, null, null, null), accounts, json);
        PaymentProvider stripe = new StripeConnectPaymentProvider(
                new StripeProperties(null, null, null, null), accounts, json);
        PaymentProvider paypal = new PayPalPaymentProvider(
                new PayPalProperties(null, null, null, null, null, null), accounts, json);
        assertThat(dlocal.name()).isEqualTo("dlocal");
        assertThat(stripe.name()).isEqualTo("stripe");
        assertThat(paypal.name()).isEqualTo("paypal");
    }
}
