package com.yayatechandinnovations.yayasales.platform.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §5.4: money is exact, integer minor units, and never silently cross-currency. */
class MoneyTest {

    @Test
    void arithmeticIsExact() {
        Money a = Money.of(1050, "USD");
        Money b = Money.of(250, "USD");

        assertThat(a.plus(b)).isEqualTo(Money.of(1300, "USD"));
        assertThat(a.minus(b)).isEqualTo(Money.of(800, "USD"));
        assertThat(b.times(3)).isEqualTo(Money.of(750, "USD"));
        assertThat(b.negate()).isEqualTo(Money.of(-250, "USD"));
        assertThat(Money.zero("USD").isZero()).isTrue();
        assertThat(a.isGreaterThan(b)).isTrue();
    }

    @Test
    void refusesCrossCurrencyArithmetic() {
        assertThatThrownBy(() -> Money.of(100, "USD").plus(Money.of(100, "DOP")))
                .isInstanceOf(Money.CurrencyMismatchException.class);
        assertThatThrownBy(() -> Money.of(100, "USD").isGreaterThan(Money.of(100, "EUR")))
                .isInstanceOf(Money.CurrencyMismatchException.class);
    }

    @Test
    void validatesCurrencyCode() {
        assertThatThrownBy(() -> Money.of(1, "usd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(1, "US")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void overflowFailsLoudly() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "USD").plus(Money.of(1, "USD")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE / 2, "USD").times(3))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void fxConvertsWithBankersRoundingAndKeepsMinorUnits() {
        FxRate usdToDop = new FxRate("USD", "DOP", new BigDecimal("58.7345"), Instant.now());

        // 1000 × 58.7345 = 58734.5 → HALF_EVEN takes the even neighbour.
        assertThat(usdToDop.convert(Money.of(1000, "USD"))).isEqualTo(Money.of(58734, "DOP"));
        // HALF_EVEN: 2.5 rounds to the even neighbour, drift-free over volume.
        FxRate half = new FxRate("USD", "DOP", new BigDecimal("2.5"), Instant.now());
        assertThat(half.convert(Money.of(1, "USD"))).isEqualTo(Money.of(2, "DOP"));
        assertThat(half.convert(Money.of(3, "USD"))).isEqualTo(Money.of(8, "DOP"));
    }

    @Test
    void fxRefusesWrongBaseCurrencyAndBadRates() {
        FxRate rate = new FxRate("USD", "DOP", BigDecimal.ONE, Instant.now());
        assertThatThrownBy(() -> rate.convert(Money.of(100, "EUR")))
                .isInstanceOf(Money.CurrencyMismatchException.class);
        assertThatThrownBy(() -> new FxRate("USD", "DOP", BigDecimal.ZERO, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
