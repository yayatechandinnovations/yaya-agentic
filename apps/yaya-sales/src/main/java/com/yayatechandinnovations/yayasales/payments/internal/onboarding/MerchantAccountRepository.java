package com.yayatechandinnovations.yayasales.payments.internal.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantAccountRepository
        extends JpaRepository<MerchantAccountEntity, MerchantAccountEntity.PK> {

    Optional<MerchantAccountEntity> findByStoreIdAndProvider(String storeId, String provider);

    List<MerchantAccountEntity> findByStoreId(String storeId);

    /** The PSP webhook carries our tracking_ref, not the store — resolve the row by it. */
    Optional<MerchantAccountEntity> findByTrackingRef(String trackingRef);

    /** Stripe's account.updated webhook correlates by the connected-account id. */
    Optional<MerchantAccountEntity> findByProviderAndAccountId(String provider, String accountId);
}
