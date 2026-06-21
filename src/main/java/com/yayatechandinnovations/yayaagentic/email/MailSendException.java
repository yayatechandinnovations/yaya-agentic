package com.yayatechandinnovations.yayaagentic.email;

/**
 * Thrown by {@link MailSender} implementations. {@link #isTransient()}
 * tells the drainer whether to schedule a retry (true → exponential
 * backoff) or DEAD-letter immediately (false).
 *
 * <p>Rule of thumb: HTTP 5xx + connection-level errors are transient;
 * HTTP 4xx (other than 429) are permanent.</p>
 */
public class MailSendException extends RuntimeException {

    private final boolean isTransient;

    public MailSendException(String message, boolean isTransient) {
        super(message);
        this.isTransient = isTransient;
    }

    public MailSendException(String message, Throwable cause, boolean isTransient) {
        super(message, cause);
        this.isTransient = isTransient;
    }

    public boolean isTransient() { return isTransient; }
}
