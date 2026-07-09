package com.yayatechandinnovations.yayasales.orders;

import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.stores.ChannelView;

import java.util.List;

/**
 * §8.1 — draft orders, the conversational-selling primitive: the agent or
 * merchant assembles an order (approved one-off prices allowed), and only
 * {@link #send} makes it real — reserving stock, re-quoting through pricing,
 * and starting the tender ("here's your order — tap to pay"). While DRAFT
 * nothing is held; cancel is the plain order cancel (DRAFT → CANCELLED).
 */
public interface DraftOrderService {

    /**
     * Lines are priced from the catalog LIST price unless an explicit
     * {@code unitPriceMinor} override is given — the merchant/agent-approved
     * one-off price, recorded in the order event log. No stock moves or holds.
     */
    OrderView create(StoreId store, DraftSpec spec, String actor);

    /** Replace the lines (re-priced the same way). Legal only while DRAFT. */
    OrderView update(OrderId id, List<DraftLine> lines, String actor);

    /**
     * THE key transition (§8.1): reserve stock only NOW (FIRM holds, checkout
     * TTL — an insufficient line fails the send and leaves the draft intact),
     * re-quote with the region's tax, move DRAFT → PENDING_PAYMENT, and begin
     * the tender. Returns the same outcome shapes as checkout so "tap to pay"
     * renders exactly like a cart checkout.
     */
    CheckoutService.CheckoutOutcome send(OrderId id, SendCommand command, String actor);

    /**
     * @param channel null resolves as WEB_CHAT (the chat is where drafts are
     *        built); @param region null falls back per §6.1 resolution.
     */
    record DraftSpec(ChannelView.Kind channel, RegionId region, String customerRef,
                     List<DraftLine> lines, String note) {}

    /** {@code unitPriceMinor} null = catalog list price; set = the approved override. */
    record DraftLine(VariantId sku, long qty, Long unitPriceMinor) {}

    /**
     * @param tender null defaults to PSP (§8.1 "tap to pay").
     * @param idempotencyKey optional — when given, a retried send re-attaches
     *        to the same outcome instead of re-reserving (§5.5).
     */
    record SendCommand(TenderKind tender, boolean applyStoreCredit, String returnUrl,
                       String customerRef, String idempotencyKey) {}
}
