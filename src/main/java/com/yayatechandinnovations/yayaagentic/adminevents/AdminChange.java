package com.yayatechandinnovations.yayaagentic.adminevents;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One committed change to an admin-visible resource. {@code tenant} is null for
 * platform-level resources (tenants, shared templates); those reach every
 * subscriber with {@code id} withheld, since the subscriber may not be allowed
 * to know which platform row changed — only that its list is stale.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminChange(
        @JsonIgnore String eventId,
        @JsonIgnore long seq,
        String tenant,
        String kind,
        String id,
        Integer version,
        Op op,
        Instant at
) {
    public enum Op { CREATED, UPDATED, DELETED }

    boolean visibleTo(String subscriberTenant) {
        return tenant == null || tenant.equals(subscriberTenant);
    }

    AdminChange forSubscriber() {
        return tenant == null ? new AdminChange(eventId, seq, null, kind, null, null, op, at) : this;
    }
}
