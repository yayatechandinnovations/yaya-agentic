/**
 * Customer directory (design §9 customers + addresses). A customer is a
 * store-scoped profile — display name, contact details, structured addresses —
 * optionally linked to the verified principal subject that orders/carts carry
 * as {@code customer_ref}. Identity itself stays in yaya-identity; this module
 * holds the commerce-facing profile a merchant curates.
 */
package com.yayatechandinnovations.yayasales.customers;
