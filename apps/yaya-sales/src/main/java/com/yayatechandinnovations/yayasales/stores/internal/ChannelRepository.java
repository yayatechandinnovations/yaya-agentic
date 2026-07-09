package com.yayatechandinnovations.yayasales.stores.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChannelRepository extends JpaRepository<ChannelEntity, String> {

    List<ChannelEntity> findByStoreIdOrderByLabelAsc(String storeId);
}
