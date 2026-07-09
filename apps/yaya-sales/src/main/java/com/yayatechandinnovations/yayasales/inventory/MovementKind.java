package com.yayatechandinnovations.yayasales.inventory;

/** §7.1 — every way a unit ever moves. Sign conventions are enforced by the ledger. */
public enum MovementKind {
    RECEIPT,        // + purchasing/goods receipt (carries unit cost)
    SALE,           // − reservation commit at the deduction point
    RETURN,         // + RMA restock
    ADJUSTMENT,     // ± manual correction (requires a reason)
    TRANSFER_IN,    // + arriving from another location
    TRANSFER_OUT,   // − leaving for another location
    SHRINKAGE,      // − loss
    DAMAGE          // − damaged, not resellable
}
