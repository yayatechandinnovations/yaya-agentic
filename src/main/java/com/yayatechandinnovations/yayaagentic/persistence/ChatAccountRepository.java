package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Chat accounts ({@code chat-account-self-service-design.md} §5). Keyed by a
 * stable UUID; the phone is a mutable unique attribute. Not workspace-scoped —
 * the device pass these back is platform-signed and crosses tenants
 * ({@code chat-channel-design.md} §20).
 */
@WorkspaceUnscoped("chat accounts are platform-verified and tenant-agnostic, like the device pass they back")
public interface ChatAccountRepository extends JpaRepository<ChatAccountEntity, UUID> {

    /** The single ACTIVE account for a phone (uniqueness is enforced only among
     *  ACTIVE rows, so a soft-deleted row in its grace window is skipped). */
    Optional<ChatAccountEntity> findByPhoneE164AndStatus(String phoneE164, String status);

    default Optional<ChatAccountEntity> findActiveByPhone(String phoneE164) {
        return findByPhoneE164AndStatus(phoneE164, ChatAccountEntity.STATUS_ACTIVE);
    }
}
