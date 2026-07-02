package com.yayatechandinnovations.yayaagentic.commerce.payment;

import com.yayatechandinnovations.yayaagentic.commerce.cart.CartStore;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceOrderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceOrderRepository;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceCartRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Applies an out-of-band payment status change (from a PSP webhook) to a local
 * order: flips {@code PENDING → PAID} (or a terminal failure), and on success
 * clears the shopper's cart from both the working-memory hot path and the
 * durable projection — so the cart is emptied even if the working-memory TTL has
 * already lapsed by the time payment completes. See {@code ecommerce-chat-design.md} §11.
 */
@Service
public class PaymentStatusService {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusService.class);

    private final CommerceOrderRepository orders;
    private final CommerceCartRepository durableCarts;
    private final CartStore cartStore;

    public PaymentStatusService(CommerceOrderRepository orders,
                                CommerceCartRepository durableCarts,
                                CartStore cartStore) {
        this.orders = orders;
        this.durableCarts = durableCarts;
        this.cartStore = cartStore;
    }

    /**
     * @param orderRef the PSP order reference echoed back on the webhook
     * @param status   normalized status ({@code PAID} / {@code CANCELLED} / …)
     * @return true if a matching order was found and updated
     */
    @Transactional
    public boolean applyStatus(String orderRef, String status) {
        Optional<CommerceOrderEntity> found = orders.findByOrderRef(orderRef);
        if (found.isEmpty()) {
            log.warn("payment webhook for unknown order_ref {} (status {})", orderRef, status);
            return false;
        }
        CommerceOrderEntity order = found.get();
        if (order.getStatus().equals(status)) {
            return true;   // idempotent — a duplicate webhook is a no-op
        }
        order.setStatus(status);
        orders.save(order);

        if ("PAID".equals(status)) {
            Ids.SessionId session = new Ids.SessionId(order.getSessionId().toString());
            cartStore.clear(session);
            durableCarts.deleteById(order.getSessionId());
            log.info("order {} PAID — cart cleared for session {}", orderRef, order.getSessionId());
        }
        return true;
    }
}
