package com.yayatechandinnovations.yayaidentity.organizations;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;

import java.time.Instant;

/**
 * An organization — the identity tenant boundary. {@code parent} is reserved for
 * later nesting (design §16 Q6); v1 orgs are flat.
 */
public record Organization(OrgId id,
                           String name,
                           String slug,
                           Status status,
                           OrgId parent,
                           boolean mfaRequired,
                           Instant createdAt) {

    public enum Status { ACTIVE, SUSPENDED }
}
