package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface WhatsAppTemplateHolderRepository
        extends JpaRepository<WhatsAppTemplateHolderEntity, WhatsAppTemplateHolderEntity.PK> {

    boolean existsByTemplateIdAndTenantId(Long templateId, String tenantId);

    long deleteByTemplateIdAndTenantId(Long templateId, String tenantId);

    @WorkspaceUnscoped("a shared template's holders span workspaces; read by platform admins and to tell a holder the template is shared")
    List<WhatsAppTemplateHolderEntity> findByTemplateIdOrderByTenantIdAsc(Long templateId);

    @WorkspaceUnscoped("a shared template's holders span workspaces; read by platform admins and to tell a holder the template is shared")
    List<WhatsAppTemplateHolderEntity> findByTemplateIdIn(Collection<Long> templateIds);

    @WorkspaceUnscoped("a shared template's holders span workspaces; counted to decide whether one holder may edit or remove it")
    long countByTemplateId(Long templateId);
}
