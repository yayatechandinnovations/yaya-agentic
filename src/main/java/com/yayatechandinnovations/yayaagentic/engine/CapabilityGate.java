package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Set;

/**
 * A generic, additive collaborator that can hide whole capabilities from a
 * profile at runtime — the seam a feature uses to gate its tools behind a live
 * (no-restart) toggle without the engine knowing the domain. Default: no beans,
 * so nothing is filtered and existing profiles are unaffected.
 *
 * <p>The engine consults this both when assembling the tool set offered to the
 * LLM AND when rendering the cached prompt prefix, so a disabled capability's
 * tools are never shown to the model (non-negotiable #2), plus a dispatch-time
 * refusal for defense in depth. Not one of the design's SPIs — an internal
 * runtime hook (e.g. {@code NetworkCapabilityGate} gates play/claim behind the
 * per-tenant network settings).
 */
public interface CapabilityGate {

    /** Capability ids to hide for this (tenant, profile) right now. */
    Set<Ids.CapabilityId> disabledCapabilities(Ids.TenantId tenant, Ids.ProfileId profile);
}
