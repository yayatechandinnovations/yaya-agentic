package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Templates belong to a WhatsApp account, not to a workspace
 * (whatsapp-shared-templates-design.md §3). What a workspace may see or send
 * is decided by {@code whatsapp_template_holders}; the tenant-facing reads
 * here take the tenant for that reason.
 */
public interface WhatsAppTemplateRepository extends JpaRepository<WhatsAppTemplateEntity, Long> {

    Optional<WhatsAppTemplateEntity> findByAccountKeyAndNameAndLocale(String accountKey, String name,
                                                                      String locale);

    List<WhatsAppTemplateEntity> findByAccountKeyOrderByNameAscLocaleAsc(String accountKey);

    Optional<WhatsAppTemplateEntity> findByAccountKeyAndId(String accountKey, Long id);

    long countByAccountKey(String accountKey);

    List<WhatsAppTemplateEntity> findByStatusIn(java.util.Collection<String> statuses);

    /** Serialises submissions: one template reaches the provider once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from WhatsAppTemplateEntity t where t.id = :id")
    Optional<WhatsAppTemplateEntity> lockById(@Param("id") Long id);

    /** The templates of an account that a workspace holds. */
    @Query("""
            select t from WhatsAppTemplateEntity t
             where t.accountKey = :accountKey
               and t.id in (select h.templateId from WhatsAppTemplateHolderEntity h
                             where h.tenantId = :tenantId)
             order by t.name asc, t.locale asc
            """)
    List<WhatsAppTemplateEntity> findHeld(@Param("accountKey") String accountKey,
                                          @Param("tenantId") String tenantId);
}
