package com.yayatechandinnovations.yayaagentic.auth.service_token;

/**
 * Published after a tenant's service-token secret row changes so
 * {@link ServiceTokenAuthenticator} evicts its per-tenant cache entry.
 * Mirrors {@code TenantJwtSecretsChangedEvent}.
 */
public record TenantServiceTokenSecretChangedEvent(String tenantId) {}
