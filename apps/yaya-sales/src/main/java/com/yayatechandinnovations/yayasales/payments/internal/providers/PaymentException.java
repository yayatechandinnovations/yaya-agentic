package com.yayatechandinnovations.yayasales.payments.internal.providers;

/** A PSP call could not complete. Surfaced to the shopper as a user-safe failure. */
public class PaymentException extends RuntimeException {
    public PaymentException(String message) {
        super(message);
    }

    public PaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
