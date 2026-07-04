package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Server-side chat directory tiles ({@code chat-account-self-service-design.md} §7.1). */
@WorkspaceUnscoped("tiles belong to platform-verified chat accounts, which are tenant-agnostic")
public interface ChatAccountTileRepository
        extends JpaRepository<ChatAccountTileEntity, ChatAccountTileEntity.Key> {

    List<ChatAccountTileEntity> findByAccountId(UUID accountId);

    @Transactional
    void deleteByAccountId(UUID accountId);
}
