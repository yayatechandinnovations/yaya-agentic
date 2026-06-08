package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditInboundOriginDeniedRepository
        extends JpaRepository<AuditInboundOriginDeniedEntity, Long> {
}
