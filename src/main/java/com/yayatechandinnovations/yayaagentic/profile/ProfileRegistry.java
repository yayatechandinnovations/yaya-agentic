package com.yayatechandinnovations.yayaagentic.profile;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Optional;

public interface ProfileRegistry {
    Optional<Profile> find(Ids.TenantId tenant, Ids.ProfileId profile);
    List<Profile> listFor(Ids.TenantId tenant);
    void register(Profile profile);
}
