package com.yayatechandinnovations.yayasales.catalog.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<ProductEntity, String> {

    List<ProductEntity> findByCatalogIdOrderBySortOrderAscTitleAsc(String catalogId);

    List<ProductEntity> findByStoreIdOrderBySortOrderAscTitleAsc(String storeId);

    @Query("select p from ProductEntity p join p.variants v "
            + "where p.storeId = :storeId and v.sku = :sku")
    Optional<ProductEntity> findByStoreIdAndVariantSku(@Param("storeId") String storeId,
                                                       @Param("sku") String sku);

    @Query("select p from ProductEntity p join p.variants v where v.id = :variantId")
    Optional<ProductEntity> findByVariantId(@Param("variantId") String variantId);
}
