package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MerchantPspAccountRepository
        extends JpaRepository<MerchantPspAccountEntity, MerchantPspAccountEntity.PK> {

    Optional<MerchantPspAccountEntity> findByTenantIdAndProvider(String tenantId, String provider);

    /** The PSP webhook carries our tracking_ref, not the tenant — resolve the row by it. */
    Optional<MerchantPspAccountEntity> findByTrackingRef(String trackingRef);

    /** Stripe's account.updated webhook correlates by the connected-account id. */
    Optional<MerchantPspAccountEntity> findByProviderAndAccountId(String provider, String accountId);
}
