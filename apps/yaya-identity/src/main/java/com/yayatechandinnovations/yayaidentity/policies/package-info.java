/**
 * Policies module (design §8 module 6, §7) — the ABAC weight-bearer and the
 * Policy Decision Point. Conditional policies (deny-overrides) layer on the RBAC
 * base; the {@link com.yayatechandinnovations.yayaidentity.policies.PolicyDecisionPoint}
 * answers {@code decide(subject, action, resource, context) → Permit |
 * Deny(userSafeReason, auditReason)} with default-deny and an append-only audit
 * of every decision. Apps are PEPs; this is the sole decision point.
 */
package com.yayatechandinnovations.yayaidentity.policies;
