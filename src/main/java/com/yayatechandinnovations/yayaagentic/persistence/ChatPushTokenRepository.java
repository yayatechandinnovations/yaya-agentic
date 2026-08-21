package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.Optional;

public interface ChatPushTokenRepository extends JpaRepository<ChatPushTokenEntity, Long> {

    List<ChatPushTokenEntity> findByTenantIdAndEntryPointIdAndRecipientKey(
            String tenantId, String entryPointId, String recipientKey);

    // A device token is registered once per recipient (unique on (recipient_key,
    // token)); the stored entry_point is just whichever tile registered it. A
    // wake therefore targets ALL of the recipient's devices in the tenant — the
    // specific entry point rides the push payload so the client drains the right
    // tile. (Filtering the read by entry_point would silently drop wakes for
    // every tile except the one that happened to register first.)
    List<ChatPushTokenEntity> findByTenantIdAndRecipientKey(String tenantId, String recipientKey);

    @WorkspaceUnscoped("idempotent register keyed by (recipient_key, device token) — token is globally unique")
    Optional<ChatPushTokenEntity> findByRecipientKeyAndToken(String recipientKey, String token);

    @Modifying
    @Transactional
    @WorkspaceUnscoped("unregister on sign-out/delete keyed by (recipient_key, device token)")
    void deleteByRecipientKeyAndToken(String recipientKey, String token);
}
