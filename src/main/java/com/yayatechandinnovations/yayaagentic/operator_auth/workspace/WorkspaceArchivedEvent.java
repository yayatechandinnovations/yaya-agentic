package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import com.yayatechandinnovations.yayaagentic.core.Ids;

/**
 * Published after a workspace is archived, by either archive path. Listeners
 * close what the workspace held outside its own rows — e.g. its assignments
 * to platform WhatsApp senders (whatsapp-shared-sender-design.md §11).
 */
public record WorkspaceArchivedEvent(Ids.TenantId tenant, String actor) {}
