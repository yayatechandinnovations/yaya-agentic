package com.yayatechandinnovations.yayaagentic.commerce.payment;

/** A checkout could not be opened with the PSP. Surfaced to the shopper as a user-safe failure. */
public class PaymentException extends RuntimeException {
    public PaymentException(String message) {
        super(message);
    }

    public PaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
