package com.yayatechandinnovations.yayasales.fulfillment.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DeliveryAgentInviteRepository
        extends JpaRepository<DeliveryAgentInviteEntity, String> {

    /** Locked so a raced double-register burns the single-use invite exactly once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from DeliveryAgentInviteEntity i where i.tokenHash = :tokenHash")
    Optional<DeliveryAgentInviteEntity> lockByTokenHash(@Param("tokenHash") String tokenHash);
}
