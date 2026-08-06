package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Activated devices of chat accounts ({@code chat-experience-design.md} §5).
 * Platform-scoped like the accounts they belong to.
 */
@WorkspaceUnscoped("chat devices belong to platform-verified accounts, like the device pass they back")
public interface ChatAccountDeviceRepository
        extends JpaRepository<ChatAccountDeviceEntity, ChatAccountDeviceEntity.Key> {

    /** All rows sharing a client-generated device id — pin-unlock disambiguates
     *  by secret hash, since the id alone is not globally unique. */
    List<ChatAccountDeviceEntity> findByDeviceId(String deviceId);

    List<ChatAccountDeviceEntity> findByAccountIdOrderByLastSeenAtDesc(UUID accountId);

    Optional<ChatAccountDeviceEntity> findByAccountIdAndDeviceId(UUID accountId, String deviceId);
}
