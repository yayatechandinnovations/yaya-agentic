package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * Send failure. {@code transient=true} means the caller MAY retry; {@code
 * false} means the underlying provider rejected the message (bad number,
 * unverified sender, etc.) and retry is pointless.
 */
public class SmsSendException extends Exception {

    private final boolean isTransient;

    public SmsSendException(String message, boolean isTransient) {
        super(message);
        this.isTransient = isTransient;
    }

    public SmsSendException(String message, Throwable cause, boolean isTransient) {
        super(message, cause);
        this.isTransient = isTransient;
    }

    public boolean isTransient() { return isTransient; }
}
