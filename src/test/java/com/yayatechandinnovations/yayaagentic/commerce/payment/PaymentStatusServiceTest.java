package com.yayatechandinnovations.yayaagentic.commerce.payment;

import com.yayatechandinnovations.yayaagentic.commerce.cart.CartStore;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceCartRepository;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceOrderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceOrderRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentStatusServiceTest {

    private final CommerceOrderRepository orders = mock(CommerceOrderRepository.class);
    private final CommerceCartRepository durableCarts = mock(CommerceCartRepository.class);
    private final CartStore cartStore = mock(CartStore.class);
    private final PaymentStatusService service = new PaymentStatusService(orders, durableCarts, cartStore);

    private static final UUID SESSION = UUID.randomUUID();

    private CommerceOrderEntity pendingOrder() {
        return new CommerceOrderEntity(UUID.randomUUID(), SESSION, "t1", "dlocal",
                "ord_abc_2598", 2598, "USD", "PENDING");
    }

    @Test
    void paidFlipsOrderAndClearsBothCarts() {
        CommerceOrderEntity order = pendingOrder();
        when(orders.findByOrderRef("ord_abc_2598")).thenReturn(Optional.of(order));

        assertTrue(service.applyStatus("ord_abc_2598", "PAID"));

        assertEquals("PAID", order.getStatus());
        verify(orders).save(order);
        verify(cartStore).clear(new Ids.SessionId(SESSION.toString()));
        verify(durableCarts).deleteById(SESSION);
    }

    @Test
    void cancelledUpdatesStatusButLeavesTheCart() {
        CommerceOrderEntity order = pendingOrder();
        when(orders.findByOrderRef("ord_abc_2598")).thenReturn(Optional.of(order));

        service.applyStatus("ord_abc_2598", "CANCELLED");

        assertEquals("CANCELLED", order.getStatus());
        verify(cartStore, never()).clear(any());
        verify(durableCarts, never()).deleteById(any());
    }

    @Test
    void duplicateWebhookIsIdempotent() {
        CommerceOrderEntity order = new CommerceOrderEntity(UUID.randomUUID(), SESSION, "t1", "dlocal",
                "ord_abc_2598", 2598, "USD", "PAID");
        when(orders.findByOrderRef("ord_abc_2598")).thenReturn(Optional.of(order));

        assertTrue(service.applyStatus("ord_abc_2598", "PAID"));
        verify(orders, never()).save(any());
        verify(cartStore, never()).clear(any());
    }

    @Test
    void unknownOrderIsANoOp() {
        when(orders.findByOrderRef("nope")).thenReturn(Optional.empty());
        assertFalse(service.applyStatus("nope", "PAID"));
        verify(orders, never()).save(any());
    }
}
