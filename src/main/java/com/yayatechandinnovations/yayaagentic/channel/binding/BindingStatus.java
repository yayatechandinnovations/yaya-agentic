package com.yayatechandinnovations.yayaagentic.channel.binding;

/**
 * Lifecycle state of a {@link ChannelBinding}. Mirrors the CHECK constraint
 * in {@code V26__channel_bindings.sql}.
 *
 * <ul>
 *   <li>{@code ACTIVE}      — binding participates in routing.</li>
 *   <li>{@code PAUSED}      — operator-paused. Inbound still acked, but the
 *       binding doesn't resolve (resolver chain falls through).</li>
 *   <li>{@code DEACTIVATED} — soft-deleted; never resolves.</li>
 * </ul>
 */
public enum BindingStatus {
    ACTIVE,
    PAUSED,
    DEACTIVATED;

    public boolean isRoutable() {
        return this == ACTIVE;
    }
}
