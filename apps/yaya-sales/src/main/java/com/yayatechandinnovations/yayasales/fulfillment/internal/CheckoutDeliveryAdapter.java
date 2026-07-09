package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.AddressValidator;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryOption;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryQuoteEngine;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryQuoteRequest;
import com.yayatechandinnovations.yayasales.fulfillment.DeliverySlots;
import com.yayatechandinnovations.yayasales.orders.CheckoutDelivery;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.SlotId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Fulfillment's side of the orders-declared {@link CheckoutDelivery} port —
 * how §8.2 steps 1/2a-b reach the quote engine, validator and slots without
 * the orders module ever depending on this one.
 */
@Component
class CheckoutDeliveryAdapter implements CheckoutDelivery {

    private final AddressValidator validator;
    private final DeliveryQuoteEngine quoteEngine;
    private final DeliverySlots slots;

    CheckoutDeliveryAdapter(AddressValidator validator, DeliveryQuoteEngine quoteEngine,
                            DeliverySlots slots) {
        this.validator = validator;
        this.quoteEngine = quoteEngine;
        this.slots = slots;
    }

    @Override
    public Address validate(StoreId store, RegionId region, Address shipTo) {
        return validator.validate(store, region, shipTo);
    }

    @Override
    public Quote quote(StoreId store, RegionId region, Address shipTo, DeliveryMethodId method,
                       List<QuoteLine> lines, Money goodsValue) {
        List<DeliveryOption> options = quoteEngine.quote(new DeliveryQuoteRequest(store, region,
                shipTo,
                lines.stream().map(l -> new DeliveryQuoteRequest.Line(l.sku(), l.qty())).toList(),
                goodsValue));
        // Several service levels on one method → the shopper's cheapest stands
        // in; picking a specific level is the delivery/options flow's job.
        return options.stream()
                .filter(o -> o.method().equals(method))
                .min(Comparator.comparingLong(o -> o.price().amountMinor()))
                .map(o -> new Quote(o.method(), o.kind().name(), o.label(), o.price(),
                        o.promisedFrom(), o.promisedTo(), o.carrier(), o.serviceLevel()))
                .orElseThrow(() -> new ApiErrors.UnprocessableException(
                        "delivery method " + method.value() + " is not offered for this address"));
    }

    @Override
    public void bookSlot(SlotId slot, OrderId order) {
        slots.book(slot, order);
    }

    @Override
    public void releaseSlot(SlotId slot, OrderId order) {
        slots.release(slot, order);
    }
}
