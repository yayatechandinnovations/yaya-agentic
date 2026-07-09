package com.yayatechandinnovations.yayaidentity.roles;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;

/**
 * The scope a role assignment applies within (design §5.5). {@code Org} covers
 * every resource in the org; {@code Resource} covers exactly one resource (e.g.
 * {@code store:st_123}) — the primitive that makes "manager of store A but not
 * store B" native.
 */
public sealed interface Scope permits Scope.Org, Scope.Resource {

    record Org(OrgId org) implements Scope {}

    record Resource(String type, String id) implements Scope {}

    /** Compact form for the token {@code roles} claim: {@code "org"} or {@code "store:st_123"}. */
    default String asClaim() {
        return switch (this) {
            case Org ignored -> "org";
            case Resource r -> r.type() + ":" + r.id();
        };
    }
}
