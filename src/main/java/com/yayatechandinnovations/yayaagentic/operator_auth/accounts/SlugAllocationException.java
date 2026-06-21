package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

/**
 * Slug allocator exhausted its collision-retry budget. Vanishingly rare in
 * practice (would require 5 specific simultaneous collisions). The
 * controller advice maps this to 500 — the operator can pick a more
 * distinctive display name and retry.
 */
public class SlugAllocationException extends RuntimeException {
    public SlugAllocationException(String message) { super(message); }
}
