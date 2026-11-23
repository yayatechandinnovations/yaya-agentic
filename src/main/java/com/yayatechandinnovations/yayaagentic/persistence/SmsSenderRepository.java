package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmsSenderRepository extends JpaRepository<SmsSenderEntity, String> {

    List<SmsSenderEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<SmsSenderEntity> findByTenantIdAndId(String tenantId, String id);

    /** A phone number belongs to at most one non-revoked sender platform-wide
     *  (partial UNIQUE index, V98); revoked rows stay as history and free the
     *  number for re-registration. Cross-tenant by design — backs the
     *  create-time conflict check. */
    @WorkspaceUnscoped("phone_e164 is globally unique among live senders; lookup must cross workspaces")
    Optional<SmsSenderEntity> findFirstByPhoneE164AndStatusNot(String phoneE164, String status);

    @WorkspaceUnscoped("phone_e164 is globally unique among live senders; lookup must cross workspaces")
    default Optional<SmsSenderEntity> findLiveByPhoneE164(String phoneE164) {
        return findFirstByPhoneE164AndStatusNot(phoneE164, "REVOKED");
    }

    /** Senders attached to a given campaign — used to re-gate them ACTIVE when
     *  the campaign flips APPROVED. {@code campaign_id} is a globally-unique
     *  surrogate PK; the A2P poller/registration path holds the campaign. */
    @WorkspaceUnscoped("campaign_id is a globally-unique surrogate key; A2P infra re-gates senders by campaign")
    List<SmsSenderEntity> findByCampaignId(String campaignId);

    /** Used by the sender poller: sweeps in-flight senders across all tenants.
     *  Cross-tenant by design — platform infrastructure. */
    @WorkspaceUnscoped("platform poller sweeps in-flight senders across workspaces")
    List<SmsSenderEntity> findByStatusIn(List<String> statuses);
}
