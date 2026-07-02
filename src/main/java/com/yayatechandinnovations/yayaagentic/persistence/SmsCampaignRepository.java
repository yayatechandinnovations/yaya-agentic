package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmsCampaignRepository extends JpaRepository<SmsCampaignEntity, String> {

    List<SmsCampaignEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<SmsCampaignEntity> findByTenantIdAndId(String tenantId, String id);

    /** Campaigns under one brand. {@code brand_id} is a globally-unique
     *  surrogate PK owned by a single tenant, resolved by the A2P
     *  registration/poller infra that already holds the brand. */
    @WorkspaceUnscoped("brand_id is a globally-unique surrogate key; A2P infra resolves campaigns by brand")
    List<SmsCampaignEntity> findByBrandId(String brandId);

    /** Used by the A2P poller (S1d): sweeps campaigns in non-terminal states
     *  across all tenants. Cross-tenant by design — platform infrastructure. */
    @WorkspaceUnscoped("platform A2P poller sweeps in-flight campaigns across workspaces")
    List<SmsCampaignEntity> findByStatusIn(List<String> statuses);
}
