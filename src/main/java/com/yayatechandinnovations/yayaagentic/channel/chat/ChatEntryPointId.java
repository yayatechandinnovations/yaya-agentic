package com.yayatechandinnovations.yayaagentic.channel.chat;

/**
 * Internal typed id of a {@link ChatEntryPoint}. Distinct from the public
 * {@code handle} (the opaque capability in the QR / deep link) for the same
 * reason {@code SenderId != twilioSenderSid}: the id is the stable FK target
 * for bindings / audit / metrics; the handle can be rotated to revoke a leaked
 * QR without rebuilding the entry point. See {@code chat-channel-design.md} §6.1.
 */
public record ChatEntryPointId(String value) {
    public ChatEntryPointId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ChatEntryPointId value must not be blank");
        }
    }
}
