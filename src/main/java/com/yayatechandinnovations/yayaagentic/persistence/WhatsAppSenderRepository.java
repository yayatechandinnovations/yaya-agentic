package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppSenderRepository extends JpaRepository<WhatsAppSenderEntity, String> {

    List<WhatsAppSenderEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<WhatsAppSenderEntity> findByTenantIdAndId(String tenantId, String id);

    /** Platform-owned senders (whatsapp-shared-sender-design.md §5) — they belong to no workspace. */
    @WorkspaceUnscoped("platform senders have no workspace; only platform admins list them")
    List<WhatsAppSenderEntity> findByOwnerKindOrderByCreatedAtDesc(String ownerKind);

    @WorkspaceUnscoped("platform senders have no workspace; looked up by id")
    Optional<WhatsAppSenderEntity> findByIdAndOwnerKind(String id, String ownerKind);

    /** A phone number belongs to at most one non-revoked sender platform-wide
     *  (partial UNIQUE index, V92); revoked rows stay as history and free the
     *  number for re-registration. Cross-tenant by design — it backs the
     *  create-time conflict check and Embedded Signup's draft reuse. */
    @WorkspaceUnscoped("phone_e164 is globally unique among live senders; lookup must cross workspaces")
    Optional<WhatsAppSenderEntity> findFirstByPhoneE164AndStatusNot(String phoneE164, String status);

    @WorkspaceUnscoped("phone_e164 is globally unique among live senders; lookup must cross workspaces")
    default Optional<WhatsAppSenderEntity> findLiveByPhoneE164(String phoneE164) {
        return findFirstByPhoneE164AndStatusNot(phoneE164, "REVOKED");
    }

    /** Revoked rows for a number across workspaces — tells whether a removed
     *  registration may still be live at the BSP under another workspace. */
    @WorkspaceUnscoped("a number's revoked history spans workspaces; read to refuse adopting another workspace's BSP sender")
    List<WhatsAppSenderEntity> findByPhoneE164AndStatus(String phoneE164, String status);

    /** Used by the W2 poller: sweeps senders across all tenants. Cross-tenant
     *  by design — the poller is platform infrastructure, not a tenant-facing
     *  query. */
    @WorkspaceUnscoped("platform poller sweeps in-flight senders across workspaces")
    List<WhatsAppSenderEntity> findByStatusIn(List<String> statuses);
}
