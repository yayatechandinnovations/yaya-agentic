/**
 * Knowledge sources, ingestion, and retrieval (RAG). Profiles attach
 * knowledge sources; the engine consults the {@code Retriever} per turn
 * (per the profile's retrieval-gating strategy) and folds ranked chunks
 * — wrapped as untrusted data — into the prompt with provenance. See
 * design §5.8.
 */
package com.yayatechandinnovations.yayaagentic.knowledge;
