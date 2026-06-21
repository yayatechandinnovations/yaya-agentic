package com.yayatechandinnovations.yayaagentic.email;

/**
 * A composed outbound message. {@code idempotencyKey} should be a stable
 * derivative of "what this message is about" (e.g. {@code sha256(template +
 * tokenHash)}) so a re-enqueue from a retried operation can't double-send.
 *
 * <p>{@code bodyHtml} is optional — the dev-console sender and plain-text
 * fallbacks use {@code bodyText} only.</p>
 */
public record MailMessage(
        String to,
        String subject,
        String bodyText,
        String bodyHtml,
        String templateId,
        String idempotencyKey
) {
}
