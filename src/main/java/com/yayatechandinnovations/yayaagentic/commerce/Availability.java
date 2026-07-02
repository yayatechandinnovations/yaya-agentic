package com.yayatechandinnovations.yayaagentic.commerce;

/**
 * Stock signal for a product or variant. Drives inventory-aware rendering
 * (grey out OUT items) and lets the model avoid adding phantom stock
 * ({@code ecommerce-chat-design.md} §16.6).
 */
public enum Availability {
    IN_STOCK,
    LOW,
    OUT,
    PREORDER
}
