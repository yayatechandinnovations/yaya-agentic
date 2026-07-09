/**
 * Session carts (module design {@code modules/cart/}, parent §5.3): the
 * durable, store-scoped cart the agent references by an opaque handle. Adding
 * a line takes a SOFT inventory hold (the item in your cart is really held);
 * abandonment is swept by inventory. Prices on lines are display snapshots —
 * every view re-quotes through pricing.
 */
package com.yayatechandinnovations.yayasales.cart;
