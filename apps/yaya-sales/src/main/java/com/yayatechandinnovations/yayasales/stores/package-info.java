/**
 * Selling-context configuration (module design {@code modules/stores/}, parent
 * §6.1): stores, their locations, policies (deduction point, backorder, hold
 * TTLs). S1 ships store + locations; channels and regions land with S2. Every
 * other module reads its configuration from here — stores holds the config,
 * siblings hold the behavior.
 */
package com.yayatechandinnovations.yayasales.stores;
