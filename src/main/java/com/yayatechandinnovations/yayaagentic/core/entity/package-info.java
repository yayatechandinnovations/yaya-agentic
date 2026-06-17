/**
 * Entity memory — engine-owned, session-scoped, typed memory for tool
 * outputs that downstream tools need as inputs (IDs, references, opaque
 * tokens). Anchored in
 * {@code docs/design/entity-memory-design.md} (M2.10).
 *
 * <p>The {@link com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory}
 * SPI sits next to
 * {@link com.yayatechandinnovations.yayaagentic.memory.WorkingMemory}
 * but addresses a different problem: tool-produced state with provenance,
 * scope, and visibility metadata, not engine-internal bookkeeping.</p>
 */
package com.yayatechandinnovations.yayaagentic.core.entity;
