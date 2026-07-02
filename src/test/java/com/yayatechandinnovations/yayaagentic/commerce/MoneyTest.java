package com.yayatechandinnovations.yayaagentic.commerce;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void normalizesCurrencyToUpperCase() {
        assertEquals("USD", new Money(100, "usd").currency());
    }

    @Test
    void rejectsBlankCurrency() {
        assertThrows(IllegalArgumentException.class, () -> new Money(100, " "));
    }

    @Test
    void addsAndMultipliesInMinorUnits() {
        Money unit = new Money(1299, "USD");
        assertEquals(new Money(2598, "USD"), unit.times(2));
        assertEquals(new Money(2599, "USD"), unit.times(2).plus(new Money(1, "USD")));
    }

    @Test
    void rejectsCrossCurrencyArithmetic() {
        assertThrows(IllegalArgumentException.class,
                () -> new Money(100, "USD").plus(new Money(100, "DOP")));
    }
}
