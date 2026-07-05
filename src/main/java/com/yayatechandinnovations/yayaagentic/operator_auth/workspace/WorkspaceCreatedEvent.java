package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import com.yayatechandinnovations.yayaagentic.core.Ids;

/**
 * Published (after the workspace's tenant row is persisted) whenever a new
 * workspace/tenant is created — signup's default workspace or an explicitly
 * created one. The demo provisioner listens after commit to seed the demo set
 * into the fresh tenant, decoupling account creation from demo content.
 */
public record WorkspaceCreatedEvent(Ids.TenantId tenant) {}
