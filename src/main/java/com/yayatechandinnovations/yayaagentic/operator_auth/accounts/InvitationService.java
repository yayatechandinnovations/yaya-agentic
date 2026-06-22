package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.email.MailMessage;
import com.yayatechandinnovations.yayaagentic.email.OutboxMailQueue;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService.RequestMeta;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.persistence.UserInvitationEntity;
import com.yayatechandinnovations.yayaagentic.persistence.UserInvitationRepository;
import com.yayatechandinnovations.yayaagentic.persistence.UserMembershipEntity;
import com.yayatechandinnovations.yayaagentic.persistence.UserMembershipRepository;
import com.yayatechandinnovations.yayaagentic.persistence.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Workspace invitations: create / list / revoke / accept.
 *
 * <p>The accept path is unified: signed-in callers redeem in place;
 * anonymous callers get a {@link AcceptOutcome.NeedsSignup} peek the UI
 * uses to redirect to signup with the token preserved. The signup flow
 * (see {@code DefaultOperatorAccountService.verifyEmail}) then auto-
 * redeems pending invitations addressed to the new user's email.</p>
 */
@Service
public class InvitationService {

    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserInvitationRepository invitations;
    private final UserMembershipRepository memberships;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final OutboxMailQueue mail;
    private final AccountTokens tokens;
    private final AccountAuditWriter audit;
    private final AccountMailLinks links;
    private final Duration ttl;

    public InvitationService(UserInvitationRepository invitations,
                             UserMembershipRepository memberships,
                             UserRepository users,
                             TenantRepository tenants,
                             OutboxMailQueue mail,
                             AccountTokens tokens,
                             AccountAuditWriter audit,
                             AccountMailLinks links,
                             YayaAgenticProperties props) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.users = users;
        this.tenants = tenants;
        this.mail = mail;
        this.tokens = tokens;
        this.audit = audit;
        this.links = links;
        YayaAgenticProperties.UserAccounts ua = props.userAccounts();
        YayaAgenticProperties.UserAccounts.Invitation inv = (ua == null || ua.invitation() == null)
                ? YayaAgenticProperties.UserAccounts.Invitation.defaults() : ua.invitation();
        this.ttl = inv.tokenTtl();
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InvitationSummary> listPending(TenantId workspace) {
        List<UserInvitationEntity> rows = invitations.findByWorkspaceIdAndConsumedAtIsNull(workspace.value());
        if (rows.isEmpty()) return List.of();
        List<InvitationSummary> out = new ArrayList<>(rows.size());
        for (UserInvitationEntity i : rows) out.add(toSummary(i));
        return out;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Mint and email a fresh invitation. The CHECK in {@code user_invitations}
     * blocks OWNER role at the DB layer; this method enforces it earlier
     * with a clearer message.
     */
    @Transactional
    public InvitationSummary create(TenantId workspace,
                                    String email,
                                    Role role,
                                    UserId actor,
                                    RequestMeta meta) {
        if (role == Role.OWNER || role == Role.PLATFORM_ADMIN) {
            throw new IllegalArgumentException("OWNER cannot be invited; promote a member after they join");
        }
        String normalized = normalizeEmail(email);
        if (!EMAIL_RE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid email format");
        }

        TenantEntity t = tenants.findById(workspace.value())
                .orElseThrow(() -> new IllegalStateException("workspace not found: " + workspace.value()));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String rawToken = tokens.mintRawToken();
        byte[] hash = tokens.sha256(rawToken);
        UUID id = UUID.randomUUID();

        invitations.save(new UserInvitationEntity(
                id, workspace.value(), normalized, role.name(), hash,
                actor.value(), now.plus(ttl), now));

        enqueueInvitationMail(normalized, t.getDisplayName(), role, rawToken, hash);
        audit.write("INVITE_SEND", actor, workspace, meta,
                Map.of(
                        "role", role.name(),
                        "email_hash", HexFormat.of().formatHex(tokens.sha256(normalized))));
        return new InvitationSummary(
                id, workspace, normalized, role, actor, now.plus(ttl), now, null);
    }

    @Transactional
    public void revoke(UUID invitationId, UserId actor, RequestMeta meta) {
        UserInvitationEntity row = invitations.findById(invitationId)
                .orElseThrow(() -> new IllegalArgumentException("invitation not found"));
        if (row.getConsumedAt() != null) return;     // already revoked / consumed; idempotent
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        row.setConsumedAt(now);
        row.setConsumedBy(actor.value());
        audit.write("INVITE_REVOKE", actor, new TenantId(row.getWorkspaceId()), meta,
                Map.of("invitation_id", invitationId.toString()));
    }

    /**
     * Token-bearer accept. Signed-in callers redeem in place; anonymous
     * callers get back enough metadata to drive the signup-carry flow.
     *
     * <p>Idempotent for signed-in callers who already have a membership.</p>
     */
    @Transactional
    public AcceptOutcome accept(String rawToken, Optional<UserId> signedInUser, RequestMeta meta) {
        if (rawToken == null || rawToken.isBlank()) return new AcceptOutcome.Invalid();
        byte[] hash = tokens.sha256(rawToken);
        Optional<UserInvitationEntity> maybe = invitations.findPendingByTokenHash(hash);
        if (maybe.isEmpty()) return new AcceptOutcome.Invalid();

        UserInvitationEntity inv = maybe.get();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (inv.getExpiresAt() != null && inv.getExpiresAt().isBefore(now)) {
            return new AcceptOutcome.Expired();
        }

        TenantId workspace = new TenantId(inv.getWorkspaceId());
        Role role = Role.valueOf(inv.getRole());

        if (signedInUser.isEmpty()) {
            // Anon visitor — return a peek so the UI can redirect to signup
            // with the token preserved. We do NOT consume the invitation;
            // the verify-email flow will redeem it after signup.
            return new AcceptOutcome.NeedsSignup(inv.getEmail(), workspace, role, rawToken);
        }

        UserId user = signedInUser.get();

        // Idempotency: if the signed-in user already has a membership in
        // this workspace, just consume the invitation and return Accepted.
        Optional<UserMembershipEntity> existing =
                memberships.findByUserIdAndWorkspaceId(user.value(), workspace.value());
        if (existing.isEmpty()) {
            memberships.save(new UserMembershipEntity(
                    user.value(), workspace.value(), role.name(), inv.getInvitedBy(), now));
        }
        inv.setConsumedAt(now);
        inv.setConsumedBy(user.value());

        audit.write("INVITE_ACCEPT", user, workspace, meta,
                Map.of("role", role.name(), "via", "signed_in_accept"));
        return new AcceptOutcome.Accepted(user, workspace, role);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void enqueueInvitationMail(String to, String workspaceDisplay, Role role,
                                       String rawToken, byte[] tokenHash) {
        String url = links.acceptInvitationUrl(rawToken);
        String body = """
                You've been invited to join %s on yaya-agentic as %s.

                Accept the invitation:
                %s

                The invitation expires in %d days.

                If the link doesn't work in your email client, paste this token
                into the accept-invitation page directly.

                Invitation token: %s

                If you didn't expect this, you can safely ignore the email.
                """.formatted(workspaceDisplay, role.name(), url, ttl.toDays(), rawToken);
        mail.enqueue(new MailMessage(
                to,
                "You're invited to " + workspaceDisplay,
                body,
                null,
                "invitation",
                idempotencyKey("invitation", tokenHash)));
    }

    private static String idempotencyKey(String template, byte[] hash) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((template + ":").getBytes(StandardCharsets.UTF_8));
            md.update(hash);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String normalizeEmail(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static InvitationSummary toSummary(UserInvitationEntity i) {
        return new InvitationSummary(
                i.getId(),
                new TenantId(i.getWorkspaceId()),
                i.getEmail(),
                Role.valueOf(i.getRole()),
                new UserId(i.getInvitedBy()),
                i.getExpiresAt(),
                i.getCreatedAt(),
                i.getConsumedAt());
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    public record InvitationSummary(
            UUID id,
            TenantId workspaceId,
            String email,
            Role role,
            UserId invitedBy,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt,
            OffsetDateTime consumedAt
    ) {}

    /**
     * Sealed result of {@link #accept}. The controller maps each variant to
     * the appropriate HTTP shape (redirect target or JSON body).
     */
    public sealed interface AcceptOutcome {
        record Accepted(UserId userId, TenantId workspace, Role role) implements AcceptOutcome {}
        record NeedsSignup(String email, TenantId workspace, Role role, String rawTokenForCarry) implements AcceptOutcome {}
        record Expired() implements AcceptOutcome {}
        record Invalid() implements AcceptOutcome {}
    }
}
