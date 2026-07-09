package com.yayatechandinnovations.yayasales.payments;

import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenderId;

/**
 * Published (Spring application event) whenever a tender settles — webhook
 * capture, courier COD-collect, merchant manual confirm, instant store credit.
 * The orders module listens and flips the order PAID once the settled tenders
 * cover the grand total (§8.2 step 6→7). Payments never calls into orders.
 */
public record TenderSettledEvent(StoreId store, OrderId order, TenderId tender, TenderKind kind) {}
