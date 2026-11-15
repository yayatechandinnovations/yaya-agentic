package com.yayatechandinnovations.yayaagentic.headless;

import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;

/**
 * What the engine is told about the one turn of a headless run
 * ({@code docs/guides/admin-panel-sessions.md} §17), and where it reports what
 * that turn consumed.
 *
 * <p>{@code directive} is appended to the per-turn part of the system prompt
 * (never the cached prefix). {@code reservation} is the message the service
 * already took from the tenant's allowance when it accepted the run: the
 * engine settles that one instead of admitting the turn again, so a run is one
 * message whatever its rounds. Null = not metered here.</p>
 */
public final class HeadlessTurn {

    private final String directive;
    private final UsageMeter.Reservation reservation;

    private volatile long inputTokens;
    private volatile long outputTokens;
    private volatile long cacheReadTokens;
    private volatile int rounds;
    private volatile boolean modelCalled;

    public HeadlessTurn(String directive, UsageMeter.Reservation reservation) {
        this.directive = directive == null ? "" : directive;
        this.reservation = reservation;
    }

    public String directive() { return directive; }
    public UsageMeter.Reservation reservation() { return reservation; }

    /** Called by the engine once, when the turn ends however it ends. */
    public void closed(long inputTokens, long outputTokens, long cacheReadTokens, int rounds, boolean modelCalled) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.rounds = rounds;
        this.modelCalled = modelCalled;
    }

    public long inputTokens() { return inputTokens; }
    public long outputTokens() { return outputTokens; }
    public long cacheReadTokens() { return cacheReadTokens; }
    /** Model calls the turn made. */
    public int rounds() { return rounds; }
    /** True when the turn counted as a message: the model was called and did not simply fail empty. */
    public boolean modelCalled() { return modelCalled; }
}
