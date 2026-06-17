package com.yayatechandinnovations.yayaagentic.core.entity;

/**
 * Lifetime of a captured entity. v1 ships {@link #SESSION} only; the other
 * two are reserved in the type system so adding them in phase 2 doesn't
 * require a breaking change to descriptors that already declare a scope.
 */
public enum EntityScope {
    /** Lives until the session ends. The v1 default and only supported scope. */
    SESSION,
    /** Cleared on intent transition. Reserved for phase 2. */
    INTENT,
    /** Single engine turn. Reserved for phase 2. */
    TURN
}
