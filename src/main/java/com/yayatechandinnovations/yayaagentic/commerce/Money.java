package com.yayatechandinnovations.yayaagentic.commerce;

/**
 * A monetary amount in <b>integer minor units</b> (e.g. cents) with an explicit
 * ISO-4217 currency. Never a floating-point price, never a model-supplied number
 * — this is the type that makes non-negotiable #1 ("the model never touches
 * money") enforceable. See {@code ecommerce-chat-design.md} §7.2, §4.
 */
public record Money(long amountMinor, String currency) {

    public Money {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Money requires an ISO-4217 currency");
        }
        currency = currency.toUpperCase();
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amountMinor + other.amountMinor, currency);
    }

    public Money times(long qty) {
        return new Money(amountMinor * qty, currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
