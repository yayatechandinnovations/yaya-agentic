package com.yayatechandinnovations.yayaagentic.config;

/**
 * Published by the network-settings admin CRUD when a tenant's row changes.
 * {@code TenantNetworkSettingsService} listens and evicts that tenant's cached
 * settings so the next engine turn re-reads the fresh values — a play/claim
 * toggle takes effect with no restart. Carries only the tenant id.
 */
public record TenantNetworkSettingsChangedEvent(String tenantId) {}
