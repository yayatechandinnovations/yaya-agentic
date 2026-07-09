package com.yayatechandinnovations.yayasales.platform.outbox;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

/**
 * The write side of the transactional outbox (design §5.6, §13). Domain
 * modules append events in the SAME transaction as the state change — a
 * consumer can never observe a change without its event, and no event is lost
 * if the process dies mid-publish. Append outside a transaction is a
 * programming error and fails fast (propagation MANDATORY).
 */
public interface Outbox {

    void append(StoreId store, String aggregate, String aggregateId, String eventType, String payloadJson);
}
