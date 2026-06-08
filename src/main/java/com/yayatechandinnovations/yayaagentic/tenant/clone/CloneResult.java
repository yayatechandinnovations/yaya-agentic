package com.yayatechandinnovations.yayaagentic.tenant.clone;

import java.util.UUID;

/**
 * Result of a {@link CloneService} call. Always includes the resolved plan;
 * carries the {@code tenant_clone_jobs} row id and apply timestamp once the
 * write transaction has committed (dry-runs leave those null).
 */
public record CloneResult(
        UUID jobId,
        String status,                            // DRY_RUN | APPLIED | FAILED
        ClonePlan plan,
        String errorCode,                         // null when status != FAILED
        String errorMessage                       // ditto
) {}
