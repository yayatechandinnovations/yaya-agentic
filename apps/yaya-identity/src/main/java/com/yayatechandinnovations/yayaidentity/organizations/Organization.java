package com.yayatechandinnovations.yayaidentity.organizations;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;

import java.time.Instant;

/**
 * An organization — the identity tenant boundary. {@code parent} is reserved for
 * later nesting (design §16 Q6); v1 orgs are flat. {@link Profile} carries the
 * optional business details the console captures; the slug stays the immutable
 * handle even when the name is edited.
 */
public record Organization(OrgId id,
                           String name,
                           String slug,
                           Status status,
                           OrgId parent,
                           boolean mfaRequired,
                           Profile profile,
                           Instant createdAt) {

    public enum Status { ACTIVE, SUSPENDED }

    public record Profile(String description,
                          String contactEmail,
                          String contactPhone,
                          String website,
                          String country) {
        public static final Profile EMPTY = new Profile(null, null, null, null, null);
    }
}
