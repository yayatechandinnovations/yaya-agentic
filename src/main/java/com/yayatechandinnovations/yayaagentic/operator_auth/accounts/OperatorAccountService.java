package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import java.util.List;
import java.util.Optional;

/**
 * Admin-plane account lifecycle. The single entry point for signup, email
 * verification, password reset, and reading user/membership state.
 *
 * <p>Distinct from the runtime {@code Authenticator} SPI (which authenticates
 * end-user {@code Principal}s in conversations) — see
 * {@code docs/specs/user-accounts/TRD.md} §1.1.</p>
 */
public interface OperatorAccountService {

    /**
     * Enumeration-safe. The {@link SignupOutcome#created()} flag is for
     * INTERNAL caller use (audit-emission detail) — the HTTP layer always
     * returns 202 regardless of value.
     */
    SignupOutcome register(SignupRequest req, RequestMeta meta);

    /**
     * Consumes the verification token, activates the user, and (in the same
     * tx) provisions a default workspace + OWNER membership and redeems any
     * pending invitations addressed to this user's email.
     *
     * @throws AccountTokenException if the token is missing, expired, or
     *                               already consumed
     */
    VerifyOutcome verifyEmail(String rawToken, RequestMeta meta);

    /**
     * Always returns silently — the API surface is fixed at 204 to mitigate
     * email enumeration.
     */
    void requestPasswordReset(String email, RequestMeta meta);

    /**
     * Rotates the password hash AND deletes every operator-session for this
     * user in one transaction.
     *
     * @throws AccountTokenException if the token is missing, expired, or
     *                               already consumed
     */
    void confirmPasswordReset(String rawToken, char[] newPassword, RequestMeta meta);

    Optional<User> findByEmail(String email);
    Optional<User> findById(UserId id);
    List<Membership> workspacesFor(UserId userId);

    /**
     * First-login auto-provision for an HTTP-delegate-authenticated operator.
     * Idempotent — second login for the same {@code email} returns the
     * existing user without side-effects beyond {@code last_login_at}.
     */
    User provisionFromDelegate(String email, String displayName, RequestMeta meta);

    /** Per-request audit metadata threaded through every state-changing method. */
    record RequestMeta(String clientIp, String userAgent, String attemptId) {
        public static RequestMeta empty() { return new RequestMeta(null, null, null); }
    }

    record SignupOutcome(boolean created, UserId userId) {
        public static SignupOutcome createdNew(UserId id) { return new SignupOutcome(true, id); }
        public static SignupOutcome existing() { return new SignupOutcome(false, null); }
    }

    record VerifyOutcome(UserId userId,
                         com.yayatechandinnovations.yayaagentic.core.Ids.TenantId defaultWorkspaceId,
                         List<Membership> redeemedInvitations) {}
}
