package com.yayatechandinnovations.yayaagentic.auth.oidc;

/**
 * Published by the admin CRUD when a tenant's OIDC issuer rows change
 * (create, update, delete). {@code OidcAuthenticator} listens and evicts
 * that tenant's processor cache so the next auth attempt re-reads the
 * fresh rows. Carries only the tenant id — listeners reload from the
 * repository themselves.
 */
public record TenantOidcIssuersChangedEvent(String tenantId) {}
