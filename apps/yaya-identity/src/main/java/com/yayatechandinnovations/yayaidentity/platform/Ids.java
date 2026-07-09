package com.yayatechandinnovations.yayaidentity.platform;

/**
 * Typed identifier records. Keeping these as records (not raw {@code String})
 * prevents cross-wiring between e.g. {@link OrgId} and {@link UserId} at compile
 * time — the same discipline as yaya-agentic's {@code core/Ids}.
 *
 * <p>Design §5 (domain model). Domain-specific ids (OrgId, UserId, …) are used
 * from I1 onward; the platform milestone (I0) only needs {@link KeyId}.
 */
public final class Ids {

    private Ids() {}

    public record OrgId(String value) {}
    public record UserId(String value) {}
    public record GroupId(String value) {}
    public record RoleId(String value) {}
    public record PolicyId(String value) {}
    public record ClientId(String value) {}
    public record AssignmentId(String value) {}
    public record MembershipId(String value) {}

    /** A signing key id — the JWK {@code kid}. */
    public record KeyId(String value) {}

    /**
     * A permission key, {@code <app>.<resource>:<action>} (e.g.
     * {@code sales.order:refund}). App-namespaced so catalogs never collide
     * (design §5.4). The shape is validated on construction.
     */
    public record PermissionKey(String value) {
        public PermissionKey {
            // <app>.<resource>:<action>; segments are lower-kebab/snake (e.g. sales.delivery:collect-cod).
            if (value == null || !value.matches("[a-z0-9_-]+\\.[a-z0-9_-]+:[a-z0-9_-]+")) {
                throw new IllegalArgumentException(
                        "permission key must be '<app>.<resource>:<action>' (lower kebab/snake), got: " + value);
            }
        }
    }
}
