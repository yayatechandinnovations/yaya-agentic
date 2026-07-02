package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuthResolverCallRepository
        extends JpaRepository<AuthResolverCallEntity, Long> {

    Page<AuthResolverCallEntity> findByTenantIdOrderByStartedAtDesc(
            String tenantId, Pageable pageable);

    Page<AuthResolverCallEntity> findByTenantIdAndCallerSha256OrderByStartedAtDesc(
            String tenantId, String callerSha256, Pageable pageable);

    /** Chronological resolver-call timeline for one session — feeds the
     *  playground auth-flow inspector. */
    @WorkspaceUnscoped("keyed on the globally-unique session UUID; the inspector reads by session, not tenant")
    List<AuthResolverCallEntity> findBySessionIdOrderByStartedAtAsc(UUID sessionId);
}
