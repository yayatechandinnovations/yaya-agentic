package com.yayatechandinnovations.yayaidentity.platform.audit;

/**
 * One append-only audit record — an authentication event or an authorization
 * decision. Built via the fluent {@link #event} / {@link #permit} / {@link #deny}
 * factories.
 *
 * <p>Non-negotiable #3 is enforced here at construction: a {@code DENY} must
 * carry a {@code userReason} (safe to surface) AND an {@code auditReason}
 * (internal truth), and the two MUST differ. A single reason, or two equal
 * reasons, is a programming error and throws — the earliest possible point to
 * catch a leak.
 */
public record AuditEntry(String orgId,
                         String actor,
                         String eventType,
                         String subject,
                         String action,
                         String resource,
                         Decision decision,
                         String userReason,
                         String auditReason,
                         String detailJson) {

    public enum Decision { PERMIT, DENY }

    public AuditEntry {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("audit entry requires an eventType");
        }
        if (decision == Decision.DENY) {
            if (userReason == null || userReason.isBlank() || auditReason == null || auditReason.isBlank()) {
                throw new IllegalArgumentException(
                        "a DENY audit entry requires both a userReason and an auditReason (#3)");
            }
            if (userReason.equals(auditReason)) {
                throw new IllegalArgumentException(
                        "userReason and auditReason must differ (#3): the user-safe reason must not "
                                + "leak the internal audit reason");
            }
        }
    }

    /** A non-authorization event (login, token issued, key rotated, …). */
    public static AuditEntry event(String eventType, String subject, String detailJson) {
        return new AuditEntry(null, null, eventType, subject, null, null, null, null, null, detailJson);
    }

    public static AuditEntry permit(String orgId, String subject, String action, String resource) {
        return new AuditEntry(orgId, subject, "authz.decision", subject, action, resource,
                Decision.PERMIT, null, null, null);
    }

    public static AuditEntry deny(String orgId, String subject, String action, String resource,
                                  String userReason, String auditReason) {
        return new AuditEntry(orgId, subject, "authz.decision", subject, action, resource,
                Decision.DENY, userReason, auditReason, null);
    }
}
