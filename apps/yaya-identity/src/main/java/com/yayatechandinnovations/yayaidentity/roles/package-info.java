/**
 * Roles module (design §8 module 5, §5.4–5.5) — the RBAC weight-bearer. Owns the
 * permission catalog (app manifest), roles (system + custom), and SCOPED role
 * assignments: a subject holds a role within a {@link
 * com.yayatechandinnovations.yayaidentity.roles.Scope} (org-wide or one
 * resource). Effective-permission resolution over those assignments is what the
 * token {@code roles} claim and the PDP (I3) build on.
 */
package com.yayatechandinnovations.yayaidentity.roles;
