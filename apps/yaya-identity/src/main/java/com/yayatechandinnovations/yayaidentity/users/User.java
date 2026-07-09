package com.yayatechandinnovations.yayaidentity.users;

import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;

import java.time.Instant;

/**
 * A global user identity. {@code PERSON} is a human; {@code SERVICE} is a machine
 * identity (service account) that authenticates via client-credentials (I4).
 */
public record User(UserId id,
                   String primaryEmail,
                   String phone,
                   String displayName,
                   Kind kind,
                   Status status,
                   Instant createdAt) {

    public enum Kind { PERSON, SERVICE }

    public enum Status { INVITED, ACTIVE, LOCKED, DISABLED, DELETED }

    public boolean canAuthenticate() {
        return status == Status.ACTIVE;
    }
}
