package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ConversationHoldRepository
        extends JpaRepository<ConversationHoldEntity, ConversationHoldEntity.PK> {

    /** The inbound worker consults this before every engine turn. */
    Optional<ConversationHoldEntity> findByTenantIdAndEndUserPhone(String tenantId, String endUserPhone);

    /** The conversations a person is handling right now, for an operator view. */
    List<ConversationHoldEntity> findByTenantIdAndHeldUntilAfter(String tenantId, OffsetDateTime now);
}
