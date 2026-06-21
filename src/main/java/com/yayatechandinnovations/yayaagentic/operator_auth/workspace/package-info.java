/**
 * Workspace access guard — the single load-bearing predicate for the admin
 * plane. Every workspace-scoped admin endpoint resolves a {@link
 * com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceContext}
 * via {@code WorkspaceAccessGuard.require(...)} before any business logic runs.
 *
 * <p>Existence non-leak: a missing membership returns 404, not 403, with
 * a body identical to "workspace does not exist." See {@code
 * docs/specs/user-accounts/TRD.md} §3.2.</p>
 *
 * <p>Distinct from the runtime {@code auth/Authorizer} SPI, which operates
 * on {@code PermissionRequirement} inside conversations. Different trust
 * boundary; see TRD §1.1.</p>
 */
package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;
