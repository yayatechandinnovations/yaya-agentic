package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository or method that intentionally has no workspace scope.
 * Consumed by {@code RepositoryScanTest} (M5a) — every {@code @Repository}
 * method must either accept a {@link WorkspaceContext} / {@code TenantId}
 * argument or be annotated with this marker.
 *
 * <p>Allowed only on repositories whose rows are not workspace-scoped:
 * {@code users}, {@code user_workspace_memberships} read-self paths,
 * {@code user_invitations} token-bearer lookups, {@code operator_sessions},
 * {@code user_email_verification_tokens}, {@code user_password_reset_tokens},
 * and the cross-workspace audit reads available to platform admins.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface WorkspaceUnscoped {
    /** Human-readable rationale; surfaces in the scan-test failure message. */
    String value() default "";
}
