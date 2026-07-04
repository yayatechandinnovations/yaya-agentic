package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

/**
 * A phone-verified device ({@code chat-channel-design.md} §20). The {@code name}
 * is self-asserted (typed by the user, not verified); only the phone carries the
 * platform's verification.
 *
 * <p>{@code accountId} and {@code passGen} tie the device to a stable chat
 * account and its revocation generation ({@code chat-account-self-service-design.md}
 * §5.2). They are null on a legacy pass minted before accounts were id-keyed and
 * on synthesised (playground) devices — callers grandfather those.
 */
public record VerifiedDevice(String phoneE164, String name, String deviceId,
                             String accountId, Long passGen) {

    public VerifiedDevice(String phoneE164, String name, String deviceId) {
        this(phoneE164, name, deviceId, null, null);
    }
}
