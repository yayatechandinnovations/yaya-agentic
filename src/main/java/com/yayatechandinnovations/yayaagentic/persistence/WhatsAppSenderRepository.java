package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppSenderRepository extends JpaRepository<WhatsAppSenderEntity, String> {

    List<WhatsAppSenderEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<WhatsAppSenderEntity> findByTenantIdAndId(String tenantId, String id);

    /** Phone numbers are globally unique in {@code whatsapp_senders} (UNIQUE
     *  constraint in V25). This lookup IS cross-tenant by design — it backs
     *  the create-time conflict check and the inbound-webhook sender resolve. */
    @WorkspaceUnscoped("phone_e164 is globally unique; lookup must cross workspaces")
    Optional<WhatsAppSenderEntity> findByPhoneE164(String phoneE164);

    /** Used by the W2 poller: sweeps senders across all tenants. Cross-tenant
     *  by design — the poller is platform infrastructure, not a tenant-facing
     *  query. */
    @WorkspaceUnscoped("platform poller sweeps in-flight senders across workspaces")
    List<WhatsAppSenderEntity> findByStatusIn(List<String> statuses);
}
