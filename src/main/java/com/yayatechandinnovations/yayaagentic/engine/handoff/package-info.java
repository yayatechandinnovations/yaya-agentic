/**
 * Continuing a conversation on another channel by copying its context into
 * the session of the new channel — never by sharing the session. The copy is
 * one {@code SYSTEM} turn on the target, framed to the model as data; the
 * source is left as it was. Contract: {@code docs/guides/admin-panel-sessions.md}
 * §6 (what else is open) and §18 (the hand-off).
 */
package com.yayatechandinnovations.yayaagentic.engine.handoff;
