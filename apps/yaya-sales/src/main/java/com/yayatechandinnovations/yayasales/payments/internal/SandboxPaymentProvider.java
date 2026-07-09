package com.yayatechandinnovations.yayasales.payments.internal;

import com.yayatechandinnovations.yayasales.payments.PaymentProvider;
import com.yayatechandinnovations.yayasales.platform.Ids;
import org.springframework.stereotype.Component;

/**
 * The in-process PSP used by tests and local dev: authorize hands back a fake
 * hosted URL and a stable psp ref; "the shopper paying" is simulated by
 * POSTing the sandbox webhook with that ref. dLocal/Stripe/PayPal adapters
 * port from {@code commerce/payment} at S4 behind this same SPI.
 *
 * <p>A {@code returnUrl} containing {@code sandbox=fail} makes authorization
 * fail — the deterministic trigger for saga-compensation tests.
 */
@Component
public class SandboxPaymentProvider implements PaymentProvider {

    public static final String NAME = "sandbox";
    static final String FAIL_DIRECTIVE = "sandbox=fail";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AuthorizeOutcome authorize(AuthorizeRequest request) {
        if (request.returnUrl() != null && request.returnUrl().contains(FAIL_DIRECTIVE)) {
            return new AuthorizeOutcome.Failed("sandbox authorization declined");
        }
        String pspRef = "sbx_" + Ids.newId();
        return new AuthorizeOutcome.HostedUrl(
                "https://sandbox.pay.invalid/checkout/" + pspRef
                        + (request.returnUrl() == null ? "" : "?return=" + request.returnUrl()),
                pspRef);
    }

    @Override
    public RefundOutcome refund(RefundRequest request) {
        if (request.pspRef() == null || !request.pspRef().startsWith("sbx_")) {
            return new RefundOutcome.Failed("unknown sandbox charge: " + request.pspRef());
        }
        return new RefundOutcome.Refunded("sbxr_" + Ids.newId());
    }
}
