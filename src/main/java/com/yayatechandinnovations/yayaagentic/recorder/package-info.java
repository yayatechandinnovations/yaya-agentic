/**
 * Conversation recording: where conversations live, how they're loaded back,
 * and the lifecycle operations (redact, delete, export, archive, replay).
 * <p>
 * The engine NEVER reads or writes conversation storage directly — every
 * operation goes through {@code ConversationRecorder}. Strategies
 * (Single / FanOut / Tiered / Classified) are bound at tenant or profile
 * scope and resolved per session by {@code RecorderRouter}. Decorators
 * (Encrypting, Redacting, Null) compose around concrete recorders.
 * See design §5.9.
 */
package com.yayatechandinnovations.yayaagentic.recorder;
