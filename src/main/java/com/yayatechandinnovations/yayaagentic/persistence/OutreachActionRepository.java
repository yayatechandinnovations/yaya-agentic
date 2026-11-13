package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface OutreachActionRepository extends JpaRepository<OutreachActionEntity, String> {

    Optional<OutreachActionEntity> findByTenantIdAndId(String tenantId, String id);

    /** Compare-and-set claim: exactly one caller wins a tap. */
    @Modifying
    @Query("update OutreachActionEntity a set a.consumedAt = :now, a.consumedBy = :by "
            + "where a.tenantId = :tenantId and a.id = :id and a.consumedAt is null")
    int claim(@Param("tenantId") String tenantId, @Param("id") String id, @Param("by") String by,
              @Param("now") OffsetDateTime now);

    @Modifying
    @Query("update OutreachActionEntity a set a.consumedAt = null, a.consumedBy = null "
            + "where a.tenantId = :tenantId and a.id = :id and a.resultJson is null")
    int release(@Param("tenantId") String tenantId, @Param("id") String id);

    @Modifying
    @Query("update OutreachActionEntity a set a.resultJson = :result where a.tenantId = :tenantId and a.id = :id")
    int complete(@Param("tenantId") String tenantId, @Param("id") String id, @Param("result") String resultJson);

    @WorkspaceUnscoped("platform housekeeping sweeps expired action rows across tenants")
    @Modifying
    @Query("delete from OutreachActionEntity a where a.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") OffsetDateTime cutoff);
}
