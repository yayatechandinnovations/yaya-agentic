package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

/**
 * A phone-verified device ({@code chat-channel-design.md} §20). The {@code name}
 * is self-asserted (typed by the user, not verified); only the phone carries the
 * platform's verification.
 */
public record VerifiedDevice(String phoneE164, String name, String deviceId) {}
