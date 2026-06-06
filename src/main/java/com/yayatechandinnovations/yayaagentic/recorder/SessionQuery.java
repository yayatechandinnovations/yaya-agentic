package com.yayatechandinnovations.yayaagentic.recorder;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;
import java.util.Optional;

public record SessionQuery(
        Ids.TenantId tenant,
        Optional<String> principalSubject,
        Optional<Ids.ProfileId> profile,
        Optional<String> textMatch,
        Optional<Instant> from,
        Optional<Instant> to,
        int page,
        int pageSize
) {}
