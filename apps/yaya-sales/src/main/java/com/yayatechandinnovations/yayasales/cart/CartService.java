package com.yayatechandinnovations.yayasales.cart;

import com.yayatechandinnovations.yayasales.platform.Ids.CartId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.pricing.PricedQuote;
import com.yayatechandinnovations.yayasales.stores.ChannelView;

import java.util.Optional;

public interface CartService {

    /** Bind a fresh cart to the resolved (channel, region) selling context (§6.1). */
    CartView open(StoreId store, ChannelView.Kind channel, Optional<RegionId> regionHint,
                  String customerRef);

    Optional<CartView> cart(CartId id);

    /** Adds qty (soft-holding it, §5.3); an existing line grows by qty. */
    CartResult add(CartId id, VariantId sku, long qty);

    CartResult setQty(CartId id, VariantId sku, long qty);

    CartView remove(CartId id, VariantId sku);

    /** Clears lines AND releases their soft holds. */
    CartView clear(CartId id);

    /** The authoritative totals — re-quoted through pricing on every call. */
    PricedQuote quote(CartId id, String couponCode);

    /** Anonymous → authenticated merge on login: quantities sum; source is emptied. */
    CartView merge(CartId from, CartId into);

    /** Checkout's read: lines WITH their hold ids (§8.2 step 2 revalidates them). */
    CheckoutHandoff peek(CartId id);

    /**
     * Empty the cart WITHOUT releasing holds — they now belong to the placed
     * order (§8.2 step 8). {@link #clear} is the shopper's "empty my cart";
     * this is the saga's "the cart became an order".
     */
    void handOff(CartId id);

    record CheckoutHandoff(CartView cart, java.util.List<HandoffLine> lines) {

        public record HandoffLine(com.yayatechandinnovations.yayasales.platform.Ids.VariantId sku,
                                  String title, long qty, long unitPriceMinor,
                                  java.util.List<String> reservationIds) {}
    }
}
