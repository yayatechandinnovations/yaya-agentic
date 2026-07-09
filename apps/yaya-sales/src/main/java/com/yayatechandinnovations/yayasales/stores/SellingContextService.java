package com.yayatechandinnovations.yayasales.stores;

import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Ids.ChannelId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.util.List;
import java.util.Optional;

/**
 * Channels & regions (§6.1) — authoring plus the resolution rule (decision 11):
 * derive the region from the ship-to when given, else the channel's default
 * (v1: the store's first region); single-region stores never prompt.
 */
public interface SellingContextService {

    ChannelView addChannel(StoreId store, ChannelView.Kind kind, String label);

    List<ChannelView> channelsOf(StoreId store);

    RegionView addRegion(StoreId store, String name, String currency, TaxPolicy tax,
                         List<TenderKind> tenders);

    RegionView updateRegion(RegionId id, String name, TaxPolicy tax, List<TenderKind> tenders);

    List<RegionView> regionsOf(StoreId store);

    Optional<RegionView> region(RegionId id);

    /** Resolve by channel kind (the agent entry point names the surface, not a channel id). */
    SellingContext resolve(StoreId store, ChannelView.Kind channelKind, Optional<RegionId> regionHint);

    SellingContext resolveByIds(StoreId store, ChannelId channel, RegionId region);
}
