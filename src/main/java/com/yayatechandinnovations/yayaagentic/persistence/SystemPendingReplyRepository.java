package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SystemPendingReplyRepository extends JpaRepository<SystemPendingReplyEntity, Long> {

    /** Pending replies for a session, newest first (most-recent disambiguation default, §8.3). */
    @WorkspaceUnscoped("keyed by the globally-unique engine session id; resolved for the session's own owner")
    List<SystemPendingReplyEntity> findBySessionIdAndStatusOrderByCreatedAtDesc(
            String sessionId, String status);
}
