/**
 * Per-session ephemeral key-value memory. Lifetimes match the session;
 * Redis is the default backing store. Slot values, partial tool args, and
 * the most recent tool result land here so the engine can render them
 * back into the prompt without re-reading from the recorder every turn.
 * See design §5.7 (Memory layers).
 */
package com.yayatechandinnovations.yayaagentic.memory;
