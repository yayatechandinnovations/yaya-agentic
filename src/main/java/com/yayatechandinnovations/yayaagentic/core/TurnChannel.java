package com.yayatechandinnovations.yayaagentic.core;

import java.util.Set;

/**
 * The surface a turn came in on, in the fixed vocabulary tools see as
 * {@code {session.channel}}: {@link #APP} for the token-authenticated sessions
 * API, {@link #WHATSAPP}, {@link #SMS}, and {@link #CHAT} for the public chat
 * app. It is the channel of the turn, not the label a session was created
 * with: that label is free text on the sessions API.
 */
public final class TurnChannel {

    public static final String APP = "app";
    public static final String WHATSAPP = "whatsapp";
    public static final String SMS = "sms";
    public static final String CHAT = "chat";

    /** {@link ExecutionContext#attributes()} key the engine stores it under. */
    public static final String ATTRIBUTE = "channel";

    private static final Set<String> MESSAGING = Set.of(WHATSAPP, SMS, CHAT);

    private TurnChannel() {}

    /**
     * @param entry          what the entry point that received the turn declared, or null
     * @param sessionChannel the session's stored channel, used when the entry point declared nothing
     */
    public static String of(String entry, String sessionChannel) {
        String source = entry != null && !entry.isBlank() ? entry : sessionChannel;
        if (source == null) return APP;
        String normalized = source.trim().toLowerCase(java.util.Locale.ROOT);
        return MESSAGING.contains(normalized) ? normalized : APP;
    }
}
