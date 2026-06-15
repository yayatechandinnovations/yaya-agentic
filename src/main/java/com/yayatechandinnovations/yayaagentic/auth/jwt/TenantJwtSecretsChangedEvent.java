package com.yayatechandinnovations.yayaagentic.auth.jwt;

/**
 * Published by the admin CRUD when a tenant's shared-secret JWT rows
 * change. {@code SharedSecretJwtAuthenticator} listens and evicts that
 * tenant's verifier cache so the next auth attempt re-reads + re-decrypts
 * the fresh rows. Same shape as
 * {@link com.yayatechandinnovations.yayaagentic.auth.oidc.TenantOidcIssuersChangedEvent}.
 */
public record TenantJwtSecretsChangedEvent(String tenantId) {}
