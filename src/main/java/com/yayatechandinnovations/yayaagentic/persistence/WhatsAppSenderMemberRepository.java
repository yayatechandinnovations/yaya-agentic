package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;

public interface WhatsAppSenderMemberRepository
        extends JpaRepository<WhatsAppSenderMemberEntity, WhatsAppSenderMemberEntity.PK> {

    List<WhatsAppSenderMemberEntity> findBySenderIdAndTenantIdOrderByPhoneE164Asc(String senderId, String tenantId);

    long countBySenderIdAndTenantId(String senderId, String tenantId);

    /** The router's phone lookup (§7.2 step A): every workspace that registered this phone here. */
    @WorkspaceUnscoped("the router resolves which workspaces a phone belongs to on a shared number")
    List<WhatsAppSenderMemberEntity> findBySenderIdAndPhoneE164(String senderId, String phoneE164);

    @Modifying
    long deleteBySenderIdAndTenantId(String senderId, String tenantId);

    @Modifying
    long deleteBySenderIdAndTenantIdAndPhoneE164(String senderId, String tenantId, String phoneE164);
}
