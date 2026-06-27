package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import org.springframework.http.HttpStatus;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an operator-plane handler (or whole controller) that must NEVER be
 * reachable by an API-key-sourced operator, regardless of role tier (§6.3):
 * minting keys, deleting workspaces, the session endpoints, the auth-strategy
 * screen. Enforced centrally by {@link RejectForApiKeysAspect} so adding a new
 * restricted endpoint is one annotation, not a documentation change.
 *
 * <p>{@link #status} defaults to 403 (forbidden-for-keys); session-oriented
 * endpoints set 400 per §6.3.</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RejectForApiKeys {
    HttpStatus status() default HttpStatus.FORBIDDEN;
    String reason() default "api_key_forbidden_endpoint";
}
