package com.yayatechandinnovations.yayaidentity.organizations;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;

import java.time.Instant;

/** An invitation for someone to join an org. The raw token is never held here — only its hash is stored. */
public record Invitation(String id,
                         OrgId org,
                         String email,
                         String role,
                         Status status,
                         Instant expiresAt,
                         Instant createdAt) {

    public enum Status { PENDING, ACCEPTED, REVOKED, EXPIRED }
}
