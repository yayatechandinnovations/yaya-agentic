package com.yayatechandinnovations.yayaagentic.core;

import java.util.Set;

/**
 * The surface a turn came in on, in the fixed vocabulary tools see as
 * {@code {session.channel}}: {@link #APP} for the token-authenticated sessions
 * API, {@link #WHATSAPP}, {@link #SMS}, and {@link #CHAT} for the public chat
 * app. It is the channel of the turn, not the label a session was created
 * with: that label is free text on the sessions API.
 *
 * <p>{@link #AUTOMATION} is the one value no client can declare: it is the
 * channel of a headless run ({@code docs/guides/admin-panel-sessions.md} §17),
 * set by the engine for the conversation it opens for that run and reserved on
 * the sessions API ({@link #isReserved}).
 */
public final class TurnChannel {

    public static final String APP = "app";
    public static final String WHATSAPP = "whatsapp";
    public static final String SMS = "sms";
    public static final String CHAT = "chat";
    /** A headless run: nobody is present, the answer is collected by a service. */
    public static final String AUTOMATION = "automation";

    /** {@link ExecutionContext#attributes()} key the engine stores it under. */
    public static final String ATTRIBUTE = "channel";

    private static final Set<String> KNOWN = Set.of(WHATSAPP, SMS, CHAT, AUTOMATION);

    private TurnChannel() {}

    /**
     * @param entry          what the entry point that received the turn declared, or null
     * @param sessionChannel the session's stored channel, used when the entry point declared nothing
     */
    public static String of(String entry, String sessionChannel) {
        String source = entry != null && !entry.isBlank() ? entry : sessionChannel;
        if (source == null) return APP;
        String normalized = source.trim().toLowerCase(java.util.Locale.ROOT);
        return KNOWN.contains(normalized) ? normalized : APP;
    }

    /** True for a session label only the engine may give: a client that sends it gets another. */
    public static boolean isReserved(String label) {
        return label != null && AUTOMATION.equals(label.trim().toLowerCase(java.util.Locale.ROOT));
    }
}
