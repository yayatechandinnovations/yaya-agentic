/**
 * The conversation runtime. Composes personality + profile + retrieval +
 * tools + recorder into a per-turn flow that the API layer streams to the
 * client over SSE. Owns intent tracking, prompt assembly, tool dispatch
 * orchestration, and replay mode. See design §6 (lifecycle), §7 (prompt
 * composition).
 */
package com.yayatechandinnovations.yayaagentic.engine;
