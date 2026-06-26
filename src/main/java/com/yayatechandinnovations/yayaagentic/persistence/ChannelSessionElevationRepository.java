package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface ChannelSessionElevationRepository
        extends JpaRepository<ChannelSessionElevationEntity, String> {

    /**
     * Session id is globally unique (channel_sessions enforces it), so the
     * tenant filter is enforced upstream by the worker. Marked
     * {@link WorkspaceUnscoped} because the column itself isn't on this row
     * — the entity carries a {@code tenant_id} but the natural lookup is by
     * the session pk.
     */
    @WorkspaceUnscoped("session_id is globally unique; channel_sessions enforces tenant isolation upstream")
    Optional<ChannelSessionElevationEntity> findBySessionId(String sessionId);

    @WorkspaceUnscoped("housekeeping job — scans across tenants by expiry alone")
    @Modifying
    @Query("DELETE FROM ChannelSessionElevationEntity e " +
           "WHERE e.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") OffsetDateTime cutoff);
}
