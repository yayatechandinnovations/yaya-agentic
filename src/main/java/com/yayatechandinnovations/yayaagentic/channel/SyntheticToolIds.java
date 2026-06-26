package com.yayatechandinnovations.yayaagentic.channel;

import com.yayatechandinnovations.yayaagentic.core.Ids;

/**
 * Reserved {@link Ids.ToolId} values used as the {@code producedBy} marker
 * on {@code EntityRef}s captured by non-tool sources — the channel adapter,
 * the engine itself, the playground simulator. The double-underscore
 * convention makes them stand out in audit and prevents collision with
 * operator-defined tool ids (which the project's tool-id pattern forbids
 * from starting with {@code __}).
 *
 * <p>See {@code whatsapp-channel-design.md} §12.1.
 */
public final class SyntheticToolIds {

    private SyntheticToolIds() {}

    /** Used by {@code InboundDispatchWorker} when capturing
     *  {@code endUserPhone}, {@code endUserChannel}, {@code endUserLocale}
     *  at session start. */
    public static final Ids.ToolId CHANNEL_WHATSAPP_INBOUND =
            new Ids.ToolId("__channel_whatsapp_inbound__");
}
