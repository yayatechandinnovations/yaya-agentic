package com.yayatechandinnovations.yayaagentic.profile;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Optional;

public interface ProfileRegistry {
    Optional<Profile> find(Ids.TenantId tenant, Ids.ProfileId profile);

    /**
     * Resolves the highest-version row for {@code profile.value()}, ignoring
     * {@code profile.version()}. Used by the engine at session start when a
     * resolver returns an unpinned ({@link Ids.ProfileId#isLatest()}) id, so a
     * newly-published profile version is served to new sessions without any
     * mapping/binding edit. See design §5.2.
     */
    Optional<Profile> findLatest(Ids.TenantId tenant, Ids.ProfileId profile);

    List<Profile> listFor(Ids.TenantId tenant);
    void register(Profile profile);
}
