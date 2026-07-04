package com.yayatechandinnovations.yayaagentic.channel.system;

/**
 * Whether an injected system message reaches the user untouched or is framed by
 * the agent. See {@code docs/design/system-messaging-design.md} §7.
 *
 * <ul>
 *   <li>{@link #VERBATIM} — body delivered as-is, no LLM round (SM-0).</li>
 *   <li>{@link #LLM_MEDIATED} — the agent relays and offers actions, treating
 *       the message as untrusted data (SM-2, §9).</li>
 * </ul>
 */
public enum MediationMode {
    VERBATIM,
    LLM_MEDIATED
}
