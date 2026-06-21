/**
 * The {@code user-password} {@code OperatorAuthenticator} strategy — the
 * default sign-in path for self-serve user accounts.
 *
 * <p>Ordered before {@code bootstrap} so a registered user always shadows
 * a username collision with the break-glass identity; yields silently when
 * the email isn't a known user so other strategies (delegate, bootstrap)
 * get a turn.</p>
 *
 * <p>See {@code docs/specs/user-accounts/TRD.md} §4.1 for chain ordering.</p>
 */
package com.yayatechandinnovations.yayaagentic.operator_auth.userpassword;
