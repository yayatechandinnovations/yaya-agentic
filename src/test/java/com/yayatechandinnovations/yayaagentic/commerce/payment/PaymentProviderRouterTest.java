package com.yayatechandinnovations.yayaagentic.commerce.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentProviderRouterTest {

    private final ObjectMapper json = new ObjectMapper();
    private final DLocalPaymentProvider dlocal =
            new DLocalPaymentProvider(new DLocalProperties(null, null, null, null, null), json);
    private final StripeConnectPaymentProvider stripe =
            new StripeConnectPaymentProvider(new StripeProperties(null, null, null), json);

    private PaymentProviderRouter router(String provider) {
        return new PaymentProviderRouter(dlocal, stripe, new PaymentRoutingProperties(provider));
    }

    @Test
    void autoDefaultsToDlocalPreservingBehavior() {
        // null and "auto" both mean auto; with neither PSP wired, dLocal wins.
        assertEquals("dlocal", router(null).name());
        assertEquals("dlocal", router("auto").name());
    }

    @Test
    void explicitStripeForcesStripe() {
        assertEquals("stripe", router("stripe").name());
        assertEquals("stripe", router("STRIPE").name());   // case-insensitive
    }

    @Test
    void explicitDlocalForcesDlocal() {
        assertEquals("dlocal", router("dlocal").name());
    }

    @Test
    void selectDelegatesTheSameProviderNameUsedForCreateCheckout() {
        // name() (used for the audit row) must match what createCheckout would pick.
        PaymentProviderRouter r = router("stripe");
        assertEquals(r.select().name(), r.name());
        assertEquals("stripe", r.select().name());
    }
}
