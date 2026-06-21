/**
 * User-account primitives for the admin plane: the {@link
 * com.yayatechandinnovations.yayaagentic.operator_auth.accounts.User} record,
 * {@link com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Membership},
 * and the {@link com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role}
 * graded grants that sit above the existing {@code Operator} identity.
 *
 * <p>See {@code docs/specs/user-accounts/TRD.md} §3 and {@code
 * docs/design/yaya-agentic-user-accounts-design.md} §2 for the conceptual model.</p>
 *
 * <p>Distinct from the runtime {@code auth/} package — that authenticates
 * end-user {@code Principal}s in conversations. This package authenticates
 * console operators. Two identity planes; see TRD §1.1.</p>
 */
package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;
