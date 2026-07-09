/**
 * Platform module — the cross-cutting foundation every other yaya-identity
 * module builds on: typed identifiers, the AES-256-GCM {@code SecretCipher},
 * the RSA {@code KeyRing} (signing keys + JWKS + rotation), the append-only
 * {@code AuditLog}, the transactional {@code OutboxRelay}, and the
 * {@code IdempotencyStore}.
 *
 * <p>Design anchor: {@code apps/yaya-identity/docs/design/yaya-identity-design.md}
 * §8 (module map, "platform") and §14 (data model). This module owns no domain
 * concepts (orgs, users, roles) — those arrive in later milestones and depend
 * on this one, never the reverse.
 */
package com.yayatechandinnovations.yayaidentity.platform;
