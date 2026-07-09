package com.yayatechandinnovations.yayasales.platform.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * An FX rate snapshot (design §5.4). Orders snapshot the rate used at pricing
 * time so a refund years later reconstructs the exact figures. The rate itself
 * is a {@link BigDecimal}; converted money stays integer minor units
 * (HALF_EVEN — banker's rounding, drift-free over many conversions).
 */
public record FxRate(String base, String quote, BigDecimal rate, Instant asOf) {

    public FxRate {
        Objects.requireNonNull(base, "base currency is required");
        Objects.requireNonNull(quote, "quote currency is required");
        Objects.requireNonNull(rate, "rate is required");
        Objects.requireNonNull(asOf, "asOf is required");
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("rate must be positive, got: " + rate);
        }
    }

    /** Convert an amount in the {@code base} currency into the {@code quote} currency. */
    public Money convert(Money money) {
        if (!money.currency().equals(base)) {
            throw new Money.CurrencyMismatchException(money.currency(), base);
        }
        long converted = BigDecimal.valueOf(money.amountMinor())
                .multiply(rate)
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValueExact();
        return new Money(converted, quote);
    }
}
