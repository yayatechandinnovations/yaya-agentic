package com.yayatechandinnovations.yayaidentity.platform.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findByOrgIdOrderByAtDesc(String orgId, Pageable pageable);
}
