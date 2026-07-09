package com.yayatechandinnovations.yayasales.fulfillment.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeliveryAgentRepository extends JpaRepository<DeliveryAgentEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DeliveryAgentEntity a where a.id = :id")
    Optional<DeliveryAgentEntity> lockById(@Param("id") String id);

    Optional<DeliveryAgentEntity> findByCredentialRef(String credentialRef);

    List<DeliveryAgentEntity> findByPhone(String phone);

    List<DeliveryAgentEntity> findByStoreIdOrderByRegisteredAtDesc(String storeId);

    List<DeliveryAgentEntity> findByStoreIdAndStatusOrderByRegisteredAtDesc(String storeId, String status);
}
