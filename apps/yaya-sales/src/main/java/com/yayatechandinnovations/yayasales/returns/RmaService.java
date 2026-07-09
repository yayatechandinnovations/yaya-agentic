package com.yayatechandinnovations.yayasales.returns;

import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.RmaId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.util.List;
import java.util.Optional;

/**
 * §8.8 — the RMA lifecycle: REQUESTED → AUTHORIZED → RECEIVED → CLOSED
 * (+ REJECTED, CANCELLED). {@link #receive} closes the physical AND financial
 * loop in one move: restockable units post RETURN movements, the refund goes
 * out through the orders refund path (store credit by default, §8.3), and the
 * order lands PARTIALLY_REFUNDED / REFUNDED.
 */
public interface RmaService {

    /**
     * Only against a PAID / FULFILLING / PARTIALLY_* / FULFILLED order's own
     * lines, and never for more than (ordered − already-RMA'd) per SKU — the
     * guard sums every previous non-rejected, non-cancelled RMA of the order.
     */
    RmaView request(StoreId store, OrderId order, List<RmaView.Line> lines, String reason,
                    String actor);

    RmaView authorize(RmaId id, String actor);

    RmaView reject(RmaId id, String reason, String actor);

    RmaView cancel(RmaId id, String actor);

    /** Reverse-logistics metadata, editable until receipt (REQUESTED/AUTHORIZED). */
    RmaView setReturnShipment(RmaId id, RmaView.ReturnShipment shipment);

    /**
     * Dispositions say what actually came back and in what state; the refund
     * defaults to the returned units' paid value (unit price × qty from the
     * order's line snapshot) and is capped by the refundable remainder the
     * tender ledger already enforces. {@code amountMinor} overrides;
     * {@code 0} receives without refunding (exchange flows).
     */
    RmaView receive(RmaId id, ReceiveCommand command, String actor);

    Optional<RmaView> rma(RmaId id);

    List<RmaView> rmasOf(StoreId store, Optional<RmaStatus> status);

    record ReceiveCommand(List<RmaView.Disposition> dispositions, boolean toStoreCredit,
                          Long amountMinor) {}
}
