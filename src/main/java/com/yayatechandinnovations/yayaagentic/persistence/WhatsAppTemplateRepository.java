package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppTemplateRepository extends JpaRepository<WhatsAppTemplateEntity, Long> {

    Optional<WhatsAppTemplateEntity> findByTenantIdAndId(String tenantId, Long id);

    List<WhatsAppTemplateEntity> findByTenantIdAndSenderIdOrderByNameAscLocaleAsc(
            String tenantId, String senderId);

    Optional<WhatsAppTemplateEntity> findByTenantIdAndSenderIdAndNameAndLocale(
            String tenantId, String senderId, String name, String locale);
}
