package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SystemSenderRepository extends JpaRepository<SystemSenderEntity, String> {

    List<SystemSenderEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<SystemSenderEntity> findByTenantIdAndId(String tenantId, String id);

    List<SystemSenderEntity> findByTenantIdAndWhatsappSenderId(String tenantId, String whatsappSenderId);
}
