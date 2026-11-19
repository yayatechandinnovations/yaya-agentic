package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WhatsAppKeywordActionRepository extends JpaRepository<WhatsAppKeywordActionEntity, Long> {

    List<WhatsAppKeywordActionEntity> findByTenantIdAndSenderIdOrderByIdAsc(String tenantId, String senderId);

    void deleteByTenantIdAndSenderId(String tenantId, String senderId);
}
