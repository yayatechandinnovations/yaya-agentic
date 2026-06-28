package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmsSenderRepository extends JpaRepository<SmsSenderEntity, String> {

    List<SmsSenderEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<SmsSenderEntity> findByTenantIdAndId(String tenantId, String id);

    /** Phone numbers are globally unique in {@code sms_senders} (UNIQUE in V44).
     *  Cross-tenant by design — backs the create-time conflict check and the
     *  inbound-webhook sender resolve. */
    @WorkspaceUnscoped("phone_e164 is globally unique; lookup must cross workspaces")
    Optional<SmsSenderEntity> findByPhoneE164(String phoneE164);

    /** Senders attached to a given campaign — used to re-gate them ACTIVE when
     *  the campaign flips APPROVED. */
    List<SmsSenderEntity> findByCampaignId(String campaignId);

    /** Used by the sender poller: sweeps in-flight senders across all tenants.
     *  Cross-tenant by design — platform infrastructure. */
    @WorkspaceUnscoped("platform poller sweeps in-flight senders across workspaces")
    List<SmsSenderEntity> findByStatusIn(List<String> statuses);
}
