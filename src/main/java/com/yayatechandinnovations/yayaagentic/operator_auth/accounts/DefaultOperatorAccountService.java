package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceCreatedEvent;
import com.yayatechandinnovations.yayaagentic.email.MailMessage;
import com.yayatechandinnovations.yayaagentic.email.OutboxMailQueue;
import com.yayatechandinnovations.yayaagentic.persistence.EmailVerificationTokenEntity;
import com.yayatechandinnovations.yayaagentic.persistence.EmailVerificationTokenRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OperatorSessionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.PasswordResetTokenEntity;
import com.yayatechandinnovations.yayaagentic.persistence.PasswordResetTokenRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.persistence.UserEntity;
import com.yayatechandinnovations.yayaagentic.persistence.UserInvitationEntity;
import com.yayatechandinnovations.yayaagentic.persistence.UserInvitationRepository;
import com.yayatechandinnovations.yayaagentic.persistence.UserMembershipEntity;
import com.yayatechandinnovations.yayaagentic.persistence.UserMembershipRepository;
import com.yayatechandinnovations.yayaagentic.persistence.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Default account lifecycle. Every state-changing path:
 *   1. validates inputs,
 *   2. runs inside a single tx,
 *   3. emits at most one {@code audit_user_events} row,
 *   4. enqueues at most one mail to the outbox (same tx — at-least-once).
 *
 * <p>Enumeration safety:
 * <ul>
 *   <li>{@link #register} returns a uniform {@link SignupOutcome} regardless
 *       of whether the email already exists. Duplicate-signup branch sends
 *       a decoy mail.</li>
 *   <li>{@link #requestPasswordReset} returns silently always; mail only
 *       fires when the user exists & is ACTIVE.</li>
 * </ul>
 * </p>
 */
@Service
public class DefaultOperatorAccountService implements OperatorAccountService {

    private static final Logger log = LoggerFactory.getLogger(DefaultOperatorAccountService.class);
    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository users;
    private final UserMembershipRepository memberships;
    private final UserInvitationRepository invitations;
    private final EmailVerificationTokenRepository verifyTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final TenantRepository tenants;
    private final OperatorSessionRepository sessions;
    private final PasswordEncoder encoder;
    private final OutboxMailQueue mail;
    private final PasswordPolicy passwordPolicy;
    private final AccountTokens tokens;
    private final WorkspaceSlugger slugger;
    private final AccountAuditWriter audit;
    private final AccountMailLinks links;
    private final ApplicationEventPublisher events;
    private final Duration verifyTtl;
    private final Duration resetTtl;

    public DefaultOperatorAccountService(UserRepository users,
                                         UserMembershipRepository memberships,
                                         UserInvitationRepository invitations,
                                         EmailVerificationTokenRepository verifyTokens,
                                         PasswordResetTokenRepository resetTokens,
                                         TenantRepository tenants,
                                         OperatorSessionRepository sessions,
                                         PasswordEncoder encoder,
                                         OutboxMailQueue mail,
                                         PasswordPolicy passwordPolicy,
                                         AccountTokens tokens,
                                         WorkspaceSlugger slugger,
                                         AccountAuditWriter audit,
                                         AccountMailLinks links,
                                         ApplicationEventPublisher events,
                                         YayaAgenticProperties props) {
        this.users = users;
        this.memberships = memberships;
        this.invitations = invitations;
        this.verifyTokens = verifyTokens;
        this.resetTokens = resetTokens;
        this.tenants = tenants;
        this.sessions = sessions;
        this.encoder = encoder;
        this.mail = mail;
        this.passwordPolicy = passwordPolicy;
        this.tokens = tokens;
        this.slugger = slugger;
        this.audit = audit;
        this.links = links;
        this.events = events;

        YayaAgenticProperties.UserAccounts ua = props.userAccounts();
        YayaAgenticProperties.UserAccounts.Verification v = (ua == null || ua.verification() == null)
                ? YayaAgenticProperties.UserAccounts.Verification.defaults() : ua.verification();
        YayaAgenticProperties.UserAccounts.Reset r = (ua == null || ua.reset() == null)
                ? YayaAgenticProperties.UserAccounts.Reset.defaults() : ua.reset();
        this.verifyTtl = v.tokenTtl();
        this.resetTtl = r.tokenTtl();
    }

    // -------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------

    @Override
    @Transactional
    public SignupOutcome register(SignupRequest req, RequestMeta meta) {
        if (req == null) throw new IllegalArgumentException("SignupRequest is required");
        String email = normalizeEmail(req.email());
        String displayName = req.displayName() == null ? "" : req.displayName().trim();
        if (!EMAIL_RE.matcher(email).matches()) {
            throw new IllegalArgumentException("invalid email format");
        }
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("displayName is required");
        }
        passwordPolicy.requireValid(req.password());

        Optional<UserEntity> existing = users.findActiveByEmail(email);
        if (existing.isPresent()) {
            UserEntity prior = existing.get();
            UserId uid = new UserId(prior.getId());
            enqueueDuplicateSignupDecoy(email, prior.getStatus());
            audit.write("SIGNUP_DUPLICATE_ATTEMPT", uid, null, meta,
                    Map.of("status", prior.getStatus()));
            return SignupOutcome.existing();
        }

        UUID newId = UUID.randomUUID();
        OffsetDateTime now = now();
        users.save(new UserEntity(
                newId,
                email,
                displayName,
                encoder.encode(CharBuffer.wrap(req.password())),
                UserStatus.PENDING_VERIFICATION.name(),
                false,
                null,
                null,
                now,
                now));

        String rawToken = tokens.mintRawToken();
        byte[] hash = tokens.sha256(rawToken);
        verifyTokens.save(new EmailVerificationTokenEntity(hash, newId, now.plus(verifyTtl), now));
        enqueueVerificationMail(email, displayName, rawToken, hash);

        UserId uid = new UserId(newId);
        audit.write("SIGNUP", uid, null, meta, Map.of());

        // Defensive: scrub the caller's password buffer regardless of what
        // they planned to do with it post-call. The controller owns the
        // buffer lifecycle but this is cheap and prevents in-memory leaks
        // if the request loop crashes between encode() and clear().
        zero(req.password());
        return SignupOutcome.createdNew(uid);
    }

    // -------------------------------------------------------------------
    // Email verification + default workspace bootstrap
    // -------------------------------------------------------------------

    @Override
    @Transactional
    public VerifyOutcome verifyEmail(String rawToken, RequestMeta meta) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AccountTokenException("token_missing");
        }
        OffsetDateTime now = now();
        byte[] hash = tokens.sha256(rawToken);
        UUID userId = verifyTokens.lookupUserIdForHash(hash);
        if (userId == null) throw new AccountTokenException("token_unknown");

        int consumed = verifyTokens.consume(hash, now);
        if (consumed == 0) throw new AccountTokenException("token_expired_or_consumed");

        UserEntity user = users.findById(userId)
                .orElseThrow(() -> new AccountTokenException("user_missing"));
        if (UserStatus.DELETED.name().equals(user.getStatus())) {
            throw new AccountTokenException("user_deleted");
        }
        user.setStatus(UserStatus.ACTIVE.name());
        user.setEmailVerifiedAt(now);
        user.setUpdatedAt(now);

        TenantId workspaceId = createDefaultWorkspace(user, now);

        // Redeem any pending invitations addressed to this email.
        List<Membership> redeemed = redeemPendingInvitationsForEmail(user, now);

        audit.write("VERIFY", new UserId(userId), workspaceId, meta,
                Map.of("default_workspace_id", workspaceId.value()));
        return new VerifyOutcome(new UserId(userId), workspaceId, redeemed);
    }

    private TenantId createDefaultWorkspace(UserEntity user, OffsetDateTime now) {
        String slug = slugger.allocate(user.getDisplayName(), tenants::existsById);
        TenantEntity t = new TenantEntity(slug, user.getDisplayName());
        t.setCreatedBy(user.getId().toString());
        t.setCode(slug);
        t.setOwnerUserId(user.getId());
        tenants.save(t);

        memberships.save(new UserMembershipEntity(
                user.getId(),
                slug,
                Role.OWNER.name(),
                user.getId(),
                now));

        audit.write("WORKSPACE_CREATE", new UserId(user.getId()), new TenantId(slug),
                null /* meta deferred — caller's meta already attached to VERIFY */,
                Map.of("code", slug, "source", "signup_default"));
        events.publishEvent(new WorkspaceCreatedEvent(new TenantId(slug)));
        return new TenantId(slug);
    }

    private List<Membership> redeemPendingInvitationsForEmail(UserEntity user, OffsetDateTime now) {
        List<UserInvitationEntity> pending = invitations.findPendingByEmail(user.getEmail());
        if (pending.isEmpty()) return List.of();
        List<Membership> minted = new ArrayList<>(pending.size());
        for (UserInvitationEntity inv : pending) {
            // Idempotency: if this user already has a membership in the
            // workspace (e.g. invited twice, accepted via another path
            // concurrently), skip the insert.
            if (memberships.findByUserIdAndWorkspaceId(user.getId(), inv.getWorkspaceId()).isPresent()) {
                inv.setConsumedAt(now);
                inv.setConsumedBy(user.getId());
                continue;
            }
            memberships.save(new UserMembershipEntity(
                    user.getId(),
                    inv.getWorkspaceId(),
                    inv.getRole(),
                    inv.getInvitedBy(),
                    now));
            inv.setConsumedAt(now);
            inv.setConsumedBy(user.getId());

            audit.write("INVITE_ACCEPT", new UserId(user.getId()),
                    new TenantId(inv.getWorkspaceId()), null,
                    Map.of("role", inv.getRole(), "via", "signup_redemption"));

            minted.add(new Membership(
                    new UserId(user.getId()),
                    new TenantId(inv.getWorkspaceId()),
                    Role.valueOf(inv.getRole()),
                    new UserId(inv.getInvitedBy()),
                    now));
        }
        return minted;
    }

    // -------------------------------------------------------------------
    // Verification resend
    // -------------------------------------------------------------------

    @Override
    @Transactional
    public void requestVerificationResend(String email, RequestMeta meta) {
        if (email == null || email.isBlank()) return;
        String normalized = normalizeEmail(email);
        if (!EMAIL_RE.matcher(normalized).matches()) return;

        Optional<UserEntity> maybe = users.findActiveByEmail(normalized);
        if (maybe.isEmpty()) return;
        UserEntity user = maybe.get();
        // Only PENDING accounts get a fresh link. ACTIVE/SUSPENDED/DELETED
        // are silent no-ops so the response can't be used to enumerate state.
        if (!UserStatus.PENDING_VERIFICATION.name().equals(user.getStatus())) return;

        OffsetDateTime now = now();
        String rawToken = tokens.mintRawToken();
        byte[] hash = tokens.sha256(rawToken);
        // Prior unconsumed tokens stay valid until natural TTL — users who
        // click an older link after requesting a new one shouldn't hit a
        // dead end. The hashed PK guarantees no collision.
        verifyTokens.save(new EmailVerificationTokenEntity(hash, user.getId(), now.plus(verifyTtl), now));
        enqueueVerificationMail(normalized, user.getDisplayName(), rawToken, hash);
        audit.write("VERIFICATION_RESEND", new UserId(user.getId()), null, meta, Map.of());
    }

    // -------------------------------------------------------------------
    // Password reset
    // -------------------------------------------------------------------

    @Override
    @Transactional
    public void requestPasswordReset(String email, RequestMeta meta) {
        if (email == null || email.isBlank()) return;
        String normalized = normalizeEmail(email);
        Optional<UserEntity> maybe = users.findActiveByEmail(normalized);
        if (maybe.isEmpty()) return;
        UserEntity user = maybe.get();
        if (!UserStatus.ACTIVE.name().equals(user.getStatus())) return;

        OffsetDateTime now = now();
        String rawToken = tokens.mintRawToken();
        byte[] hash = tokens.sha256(rawToken);
        resetTokens.save(new PasswordResetTokenEntity(hash, user.getId(), now.plus(resetTtl), now));
        enqueueResetMail(normalized, user.getDisplayName(), rawToken, hash);
        audit.write("PASSWORD_RESET_REQUEST", new UserId(user.getId()), null, meta, Map.of());
    }

    @Override
    @Transactional
    public void confirmPasswordReset(String rawToken, char[] newPassword, RequestMeta meta) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AccountTokenException("token_missing");
        }
        passwordPolicy.requireValid(newPassword);

        OffsetDateTime now = now();
        byte[] hash = tokens.sha256(rawToken);
        UUID userId = resetTokens.lookupUserIdForHash(hash);
        if (userId == null) throw new AccountTokenException("token_unknown");

        int consumed = resetTokens.consume(hash, now);
        if (consumed == 0) throw new AccountTokenException("token_expired_or_consumed");

        UserEntity user = users.findById(userId)
                .orElseThrow(() -> new AccountTokenException("user_missing"));
        user.setPasswordHash(encoder.encode(CharBuffer.wrap(newPassword)));
        user.setUpdatedAt(now);

        int killed = sessions.deleteByOperatorSubject(userId.toString());
        audit.write("PASSWORD_RESET_CONFIRM", new UserId(userId), null, meta,
                Map.of("sessions_invalidated", killed));
        zero(newPassword);
    }

    // -------------------------------------------------------------------
    // Reads + delegate provisioning
    // -------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        return users.findActiveByEmail(normalizeEmail(email)).map(DefaultOperatorAccountService::toUser);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findById(UserId id) {
        if (id == null) return Optional.empty();
        return users.findById(id.value()).map(DefaultOperatorAccountService::toUser);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Membership> workspacesFor(UserId userId) {
        if (userId == null) return List.of();
        List<UserMembershipEntity> rows = memberships.findByUserId(userId.value());
        List<Membership> out = new ArrayList<>(rows.size());
        for (UserMembershipEntity m : rows) {
            out.add(new Membership(
                    new UserId(m.getUserId()),
                    new TenantId(m.getWorkspaceId()),
                    Role.valueOf(m.getRole()),
                    m.getAddedBy() == null ? null : new UserId(m.getAddedBy()),
                    m.getAddedAt()));
        }
        return out;
    }

    @Override
    @Transactional
    public User provisionFromDelegate(String email, String displayName, RequestMeta meta) {
        String normalized = normalizeEmail(email);
        Optional<UserEntity> existing = users.findActiveByEmail(normalized);
        OffsetDateTime now = now();
        if (existing.isPresent()) {
            UserEntity u = existing.get();
            u.setLastLoginAt(now);
            return toUser(u);
        }
        UUID id = UUID.randomUUID();
        UserEntity u = new UserEntity(
                id,
                normalized,
                displayName == null || displayName.isBlank() ? normalized : displayName,
                "" /* delegate users have no usable password */,
                UserStatus.ACTIVE.name(),
                false,
                now,
                now,
                now,
                now);
        users.save(u);

        TenantId defaultWs = createDefaultWorkspace(u, now);
        audit.write("SIGNUP", new UserId(id), defaultWs, meta,
                Map.of("source", "delegate_autoprov"));
        return toUser(u);
    }

    // -------------------------------------------------------------------
    // Mail enqueue helpers — phase 2 carries plain-text bodies inline.
    // Phase 3 introduces a templating layer.
    // -------------------------------------------------------------------

    private void enqueueVerificationMail(String to, String displayName, String rawToken, byte[] tokenHash) {
        String url = links.verifyUrl(rawToken);
        String body = """
                Hi %s,

                Confirm your email to activate your yaya-agentic account.

                Click to verify:
                %s

                The link expires in %d hours.

                If the link doesn't work in your email client, you can paste this
                token into the verification page directly.

                Verification token: %s

                If you didn't sign up, ignore this email.
                """.formatted(displayName, url, verifyTtl.toHours(), rawToken);
        mail.enqueue(new MailMessage(
                to,
                "Verify your yaya-agentic account",
                body,
                null,
                "verify-email",
                idempotencyKey("verify-email", tokenHash)));
    }

    private void enqueueDuplicateSignupDecoy(String to, String priorStatus) {
        // The decoy is delivered only to the real account holder's inbox, so
        // tailoring copy by internal status does not leak enumeration. For a
        // PENDING account the original "sign in or reset password" advice is
        // a dead end — point at the resend flow instead.
        boolean pending = UserStatus.PENDING_VERIFICATION.name().equals(priorStatus);
        String body = pending ? """
                Someone tried to sign up for a yaya-agentic account with your email.

                You already started signing up but haven't verified your email yet.
                Open the most recent verification email, or request a new one from
                the sign-in page ("resend verification link").

                If it wasn't you, you can safely ignore this email — no new account
                was created.
                """ : """
                Someone tried to sign up for a yaya-agentic account with your email.

                If that was you, you already have an account — sign in instead, or
                use the password reset link if you've forgotten your password.

                If it wasn't you, you can safely ignore this email — no new account
                was created.
                """;
        mail.enqueue(new MailMessage(
                to,
                "Someone tried to sign up as you",
                body,
                null,
                "duplicate-signup",
                idempotencyKey("duplicate-signup", tokens.sha256(to + ":" + System.currentTimeMillis() / 3_600_000))));
    }

    private void enqueueResetMail(String to, String displayName, String rawToken, byte[] tokenHash) {
        String url = links.resetPasswordUrl(rawToken);
        String body = """
                Hi %s,

                A password reset was requested for your yaya-agentic account.

                Reset your password:
                %s

                The link expires in %d minutes.

                If the link doesn't work in your email client, paste this token
                into the reset page directly.

                Reset token: %s

                If you didn't ask for this, you can ignore the email — your password
                is unchanged.
                """.formatted(displayName, url, resetTtl.toMinutes(), rawToken);
        mail.enqueue(new MailMessage(
                to,
                "Reset your yaya-agentic password",
                body,
                null,
                "reset-password",
                idempotencyKey("reset-password", tokenHash)));
    }

    private String idempotencyKey(String template, byte[] hash) {
        try {
            byte[] in = (template + ":").getBytes(StandardCharsets.UTF_8);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(in);
            md.update(hash);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static User toUser(UserEntity e) {
        return new User(
                new UserId(e.getId()),
                e.getEmail(),
                e.getDisplayName(),
                UserStatus.valueOf(e.getStatus()),
                e.isPlatformAdmin(),
                e.getEmailVerifiedAt(),
                e.getLastLoginAt(),
                e.getCreatedAt());
    }

    private static String normalizeEmail(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Zero a char[] buffer — caller may also call clear() on its own copy. */
    private static void zero(char[] buf) {
        if (buf == null) return;
        for (int i = 0; i < buf.length; i++) buf[i] = '\0';
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> linked(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
