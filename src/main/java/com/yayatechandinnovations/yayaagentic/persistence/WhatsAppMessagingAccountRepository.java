package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppMessagingAccountRepository
        extends JpaRepository<WhatsAppMessagingAccountEntity, String> {

    List<WhatsAppMessagingAccountEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<WhatsAppMessagingAccountEntity> findByTenantIdAndId(String tenantId, String id);

    Optional<WhatsAppMessagingAccountEntity> findByTenantIdAndWabaId(String tenantId, String wabaId);

    /** WABA ids are globally unique (UNIQUE in V75). Cross-tenant by design —
     *  this lookup backs the ownership-conflict check: a WABA claimed by one
     *  tenant can never be onboarded by another. */
    @WorkspaceUnscoped("waba_id is globally unique; ownership-conflict check must cross workspaces")
    Optional<WhatsAppMessagingAccountEntity> findByWabaId(String wabaId);
}
