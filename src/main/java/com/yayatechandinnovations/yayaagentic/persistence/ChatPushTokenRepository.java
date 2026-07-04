package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatPushTokenRepository extends JpaRepository<ChatPushTokenEntity, Long> {

    List<ChatPushTokenEntity> findByTenantIdAndEntryPointIdAndRecipientKey(
            String tenantId, String entryPointId, String recipientKey);

    @WorkspaceUnscoped("idempotent register keyed by (recipient_key, device token) — token is globally unique")
    Optional<ChatPushTokenEntity> findByRecipientKeyAndToken(String recipientKey, String token);
}
