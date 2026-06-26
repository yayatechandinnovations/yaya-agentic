/**
 * Step-up authentication types — the SPI wire shapes that
 * {@link com.yayatechandinnovations.yayaagentic.auth.AuthzDecision.DenyOfferStepUp}
 * carries so the conversation engine can mount a verification flow when an
 * Authorizer signals "this action would be allowed at a higher trust level."
 *
 * <p>W-A4 ships the types and the AuthzDecision variant; the
 * {@code StepUpCoordinator} runtime, {@code StepUpProvider} SPI, and the
 * persistence tables ({@code step_up_pending}, {@code step_up_audit},
 * {@code channel_session_elevation}) arrive in W-A5+.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.
 */
package com.yayatechandinnovations.yayaagentic.auth.stepup;
