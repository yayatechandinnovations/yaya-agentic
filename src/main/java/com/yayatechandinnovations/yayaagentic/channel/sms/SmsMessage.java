package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * @param toPhone        recipient E.164 phone (e.g. {@code +15551234567})
 * @param fromPhone      sender E.164 phone — MUST be different from the
 *                       WhatsApp sender we're elevating (rule 1 of the
 *                       step-up security non-negotiables)
 * @param bodyText       message body
 * @param idempotencyKey stable derivative of "what this message is about";
 *                       providers that support idempotency MAY use it to
 *                       suppress double-sends on retry
 */
public record SmsMessage(
        String toPhone,
        String fromPhone,
        String bodyText,
        String idempotencyKey
) {

    public SmsMessage {
        if (toPhone == null || toPhone.isBlank()) {
            throw new IllegalArgumentException("toPhone is required");
        }
        if (!toPhone.startsWith("+")) {
            throw new IllegalArgumentException("toPhone must be E.164 (start with +)");
        }
        if (fromPhone == null || fromPhone.isBlank()) {
            throw new IllegalArgumentException("fromPhone is required");
        }
        if (bodyText == null) bodyText = "";
        if (idempotencyKey == null) idempotencyKey = "";
    }
}
