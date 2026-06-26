package com.yayatechandinnovations.yayaagentic.channel.binding;

/** Typed wrapper around the {@code channel_bindings.id} BIGSERIAL. */
public record BindingId(long value) {

    public BindingId {
        if (value <= 0) throw new IllegalArgumentException("BindingId must be positive");
    }
}
