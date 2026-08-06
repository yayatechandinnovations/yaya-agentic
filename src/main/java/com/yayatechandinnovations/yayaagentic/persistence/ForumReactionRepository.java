package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Forum reactions ({@code chat-experience-design.md} §8.1). */
@WorkspaceUnscoped("reactions belong to verified chat accounts on the public surface")
public interface ForumReactionRepository
        extends JpaRepository<ForumReactionEntity, ForumReactionEntity.Key> {

    List<ForumReactionEntity> findByPostIdAndAccountId(String postId, UUID accountId);
}
