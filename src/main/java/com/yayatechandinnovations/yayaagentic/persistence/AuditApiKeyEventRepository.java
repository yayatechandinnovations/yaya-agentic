package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditApiKeyEventRepository extends JpaRepository<AuditApiKeyEventEntity, Long> {

    /**
     * Audit rows for one key, by globally-unique {@code api_key_id}. The
     * controller has already confirmed the key belongs to the caller's
     * workspace (via {@code ApiKeyService.get}) before this read, so the query
     * needs no separate workspace scope.
     */
    @WorkspaceUnscoped("queried by globally-unique api_key_id after controller verifies workspace ownership")
    Page<AuditApiKeyEventEntity> findByApiKeyIdOrderByOccurredAtDesc(UUID apiKeyId, Pageable pageable);
}
