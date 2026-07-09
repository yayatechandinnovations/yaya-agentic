package com.yayatechandinnovations.yayasales.platform.money;

import java.util.Objects;

/**
 * Exact money: integer minor units + ISO-4217 currency (design §5.4). No float
 * exists anywhere on the money path — arithmetic is {@code long} with overflow
 * checks, and cross-currency operations are rejected rather than coerced.
 * Negative amounts are legal (refunds, adjustments, discounts); callers that
 * need non-negativity assert it at their boundary.
 */
public record Money(long amountMinor, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency is required");
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code, got: " + currency);
        }
    }

    public static Money of(long amountMinor, String currency) {
        return new Money(amountMinor, currency);
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(amountMinor, factor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amountMinor), currency);
    }

    public boolean isNegative() {
        return amountMinor < 0;
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amountMinor > other.amountMinor;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other money is required");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    public static class CurrencyMismatchException extends IllegalArgumentException {
        public CurrencyMismatchException(String a, String b) {
            super("currency mismatch: " + a + " vs " + b + " — convert explicitly with an FxRate snapshot");
        }
    }
}
