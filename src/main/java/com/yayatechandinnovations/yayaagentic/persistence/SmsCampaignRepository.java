package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmsCampaignRepository extends JpaRepository<SmsCampaignEntity, String> {

    List<SmsCampaignEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<SmsCampaignEntity> findByTenantIdAndId(String tenantId, String id);

    List<SmsCampaignEntity> findByBrandId(String brandId);

    /** Used by the A2P poller (S1d): sweeps campaigns in non-terminal states
     *  across all tenants. Cross-tenant by design — platform infrastructure. */
    @WorkspaceUnscoped("platform A2P poller sweeps in-flight campaigns across workspaces")
    List<SmsCampaignEntity> findByStatusIn(List<String> statuses);
}
