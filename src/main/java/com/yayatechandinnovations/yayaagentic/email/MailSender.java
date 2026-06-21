package com.yayatechandinnovations.yayaagentic.email;

/**
 * Provider-pluggable mail transport invoked by {@link MailOutboxDrainer}.
 * Implementations distinguish transient failures (throw {@link
 * MailSendException} with {@code transient=true} → drainer retries) from
 * permanent failures ({@code transient=false} → drainer DEAD-letters).
 *
 * <p>Services do NOT call this directly — they enqueue via
 * {@link OutboxMailQueue}. Skipping the outbox loses the at-least-once
 * guarantee.</p>
 */
public interface MailSender {

    /** Stable identifier; reported in audit + logs. */
    String name();

    /** Send synchronously. Throws on failure. Must not retry internally — the drainer owns retry. */
    void send(MailMessage msg) throws MailSendException;
}
