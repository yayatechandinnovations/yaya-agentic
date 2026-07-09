package com.yayatechandinnovations.yayasales.payments.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreCreditRepository extends JpaRepository<StoreCreditEntity, String> {

    @Query("select coalesce(sum(c.deltaMinor), 0) from StoreCreditEntity c "
            + "where c.storeId = :storeId and c.customerRef = :customerRef and c.currency = :currency")
    long balanceMinor(@Param("storeId") String storeId,
                      @Param("customerRef") String customerRef,
                      @Param("currency") String currency);
}
