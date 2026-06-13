package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ToolRepository extends JpaRepository<ToolEntity, ToolEntity.PK> {
    List<ToolEntity> findByTenantIdAndIdOrderByVersionDesc(String tenantId, String id);
    List<ToolEntity> findByTenantId(String tenantId);

    /**
     * Renames every version of a tool (the {@code id} is part of the
     * composite PK so a plain {@code save()} can't relabel it). Used by the
     * legacy-name repair flow; do NOT call from request paths.
     */
    @Modifying
    @Query(value = "UPDATE tools_registry SET id = :newId "
            + "WHERE tenant_id = :tenant AND id = :oldId", nativeQuery = true)
    int renameAcrossVersions(@Param("tenant") String tenant,
                             @Param("oldId") String oldId,
                             @Param("newId") String newId);
}
