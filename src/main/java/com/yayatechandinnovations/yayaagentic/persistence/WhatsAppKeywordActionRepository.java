package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WhatsAppKeywordActionRepository extends JpaRepository<WhatsAppKeywordActionEntity, Long> {

    List<WhatsAppKeywordActionEntity> findByTenantIdAndSenderIdOrderByIdAsc(String tenantId, String senderId);

    void deleteByTenantIdAndSenderId(String tenantId, String senderId);

    /** A platform sender's keywords across its workspaces — the router matches a ROUTED prefix to its owner. */
    @WorkspaceUnscoped("on a shared number the matched keyword names the workspace")
    List<WhatsAppKeywordActionEntity> findBySenderIdAndScopeOrderByIdAsc(String senderId, String scope);
}
