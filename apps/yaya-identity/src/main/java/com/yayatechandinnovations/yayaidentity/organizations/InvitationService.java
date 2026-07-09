package com.yayatechandinnovations.yayaidentity.organizations;

import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.platform.crypto.Tokens;
import com.yayatechandinnovations.yayaidentity.users.User;
import com.yayatechandinnovations.yayaidentity.users.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Invite-gated org onboarding (design §5.2, §10.2; US-20..US-22, AC-10). An
 * owner/admin invites by email; the invitee accepts with a token and a chosen
 * password. Accept is a SINGLE transaction: resolve-or-create the user, set the
 * password, activate the account, add the membership, and consume the invitation
 * — all or nothing.
 */
@Service
public class InvitationService {

    private final InvitationRepository invitations;
    private final OrganizationService organizations;
    private final UserService users;
    private final CredentialService credentials;

    public InvitationService(InvitationRepository invitations, OrganizationService organizations,
                             UserService users, CredentialService credentials) {
        this.invitations = invitations;
        this.organizations = organizations;
        this.users = users;
        this.credentials = credentials;
    }

    /** Create a pending invitation; returns the raw token to deliver out-of-band (only its hash is stored). */
    @Transactional
    public InviteResult invite(OrgId org, String email, String role, String invitedBy, Duration ttl) {
        if (organizations.byId(org).isEmpty()) {
            throw new IllegalArgumentException("no such org: " + org.value());
        }
        String rawToken = Tokens.generate();
        InvitationEntity e = new InvitationEntity(
                "inv_" + UUID.randomUUID().toString().replace("-", ""),
                org.value(), email, role, Tokens.sha256Hex(rawToken), invitedBy,
                Instant.now(), Instant.now().plus(ttl));
        invitations.save(e);
        return new InviteResult(e.toDomain(), rawToken);
    }

    @Transactional
    public AcceptResult accept(String rawToken, String displayName, String rawPassword) {
        InvitationEntity inv = invitations.findByTokenHash(Tokens.sha256Hex(rawToken))
                .orElseThrow(() -> new InvalidInvitationException("unknown or already-used invitation"));

        if (!Invitation.Status.PENDING.name().equals(inv.getStatus())) {
            throw new InvalidInvitationException("invitation is not pending");
        }
        if (Instant.now().isAfter(inv.getExpiresAt())) {
            inv.setStatus(Invitation.Status.EXPIRED.name());
            throw new InvalidInvitationException("invitation has expired");
        }

        User user = users.byEmail(inv.getEmail())
                .orElseGet(() -> users.createPerson(inv.getEmail(), null, displayName));
        UserId userId = user.id();

        credentials.setPassword(userId, rawPassword);
        users.setStatus(userId, User.Status.ACTIVE);
        organizations.addMember(new OrgId(inv.getOrgId()), userId);
        inv.markAccepted(Instant.now());

        return new AcceptResult(userId, new OrgId(inv.getOrgId()));
    }

    public record InviteResult(Invitation invitation, String token) {}

    public record AcceptResult(UserId user, OrgId org) {}

    public static class InvalidInvitationException extends RuntimeException {
        public InvalidInvitationException(String message) { super(message); }
    }
}
