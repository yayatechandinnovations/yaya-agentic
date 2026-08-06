package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Tenant-authored receipt templates ({@code chat-experience-design.md} §7.4). */
public interface ReceiptTemplateRepository extends JpaRepository<ReceiptTemplateEntity, String> {

    List<ReceiptTemplateEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<ReceiptTemplateEntity> findByTenantIdAndId(String tenantId, String id);
}
