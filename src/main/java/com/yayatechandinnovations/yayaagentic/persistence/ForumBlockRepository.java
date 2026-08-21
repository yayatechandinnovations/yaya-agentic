package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Forum member blocks (Apple Guideline 1.2). Public chat surface, scoped per
 *  entry point + blocker. */
@WorkspaceUnscoped("public chat surface; scoping is per entry point + blocker account")
public interface ForumBlockRepository extends JpaRepository<ForumBlockEntity, String> {

    List<ForumBlockEntity> findByEntryPointIdAndBlockerAccountIdOrderByCreatedAtDesc(
            String entryPointId, UUID blockerAccountId);

    Optional<ForumBlockEntity> findByEntryPointIdAndBlockerAccountIdAndBlockedAccountId(
            String entryPointId, UUID blockerAccountId, UUID blockedAccountId);

    Optional<ForumBlockEntity> findByIdAndBlockerAccountId(String id, UUID blockerAccountId);
}
