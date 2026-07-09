package com.yayatechandinnovations.yayasales.stores.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.ChannelId;
import com.yayatechandinnovations.yayasales.platform.Ids.PriceListId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.ChannelView;
import com.yayatechandinnovations.yayasales.stores.RegionView;
import com.yayatechandinnovations.yayasales.stores.SellingContext;
import com.yayatechandinnovations.yayasales.stores.SellingContextService;
import com.yayatechandinnovations.yayasales.stores.TaxPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class DefaultSellingContextService implements SellingContextService {

    private final ChannelRepository channels;
    private final RegionRepository regions;
    private final StoreRepository stores;
    private final ObjectMapper json;

    public DefaultSellingContextService(ChannelRepository channels, RegionRepository regions,
                                        StoreRepository stores, ObjectMapper json) {
        this.channels = channels;
        this.regions = regions;
        this.stores = stores;
        this.json = json;
    }

    @Override
    @Transactional
    public ChannelView addChannel(StoreId store, ChannelView.Kind kind, String label) {
        requireStore(store);
        if (kind == null) throw new IllegalArgumentException("channel kind is required");
        boolean duplicate = channels.findByStoreIdOrderByLabelAsc(store.value()).stream()
                .anyMatch(c -> c.getKind().equals(kind.name()));
        if (duplicate) {
            throw new ApiErrors.ConflictException("store already has a " + kind + " channel");
        }
        ChannelEntity channel = new ChannelEntity(Ids.newId(), store.value(), kind.name(),
                label == null || label.isBlank() ? kind.name() : label);
        channels.save(channel);
        return toView(channel);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChannelView> channelsOf(StoreId store) {
        return channels.findByStoreIdOrderByLabelAsc(store.value())
                .stream().map(DefaultSellingContextService::toView).toList();
    }

    @Override
    @Transactional
    public RegionView addRegion(StoreId store, String name, String currency, TaxPolicy tax,
                                List<TenderKind> tenders) {
        requireStore(store);
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code");
        }
        RegionEntity region = new RegionEntity(Ids.newId(), store.value(),
                name == null || name.isBlank() ? currency : name, currency,
                write(tax == null ? TaxPolicy.none() : tax),
                write(tenders == null || tenders.isEmpty() ? List.of(TenderKind.PSP) : tenders));
        regions.save(region);
        return toView(region);
    }

    @Override
    @Transactional
    public RegionView updateRegion(RegionId id, String name, TaxPolicy tax, List<TenderKind> tenders) {
        RegionEntity region = regions.findById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("region", id.value()));
        if (name != null && !name.isBlank()) region.setName(name);
        if (tax != null) region.setTaxJson(write(tax));
        if (tenders != null && !tenders.isEmpty()) region.setTendersJson(write(tenders));
        return toView(region);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RegionView> regionsOf(StoreId store) {
        return regions.findByStoreIdOrderByNameAsc(store.value())
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegionView> region(RegionId id) {
        return regions.findById(id.value()).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public SellingContext resolve(StoreId store, ChannelView.Kind channelKind, Optional<RegionId> regionHint) {
        ChannelView channel = channelsOf(store).stream()
                .filter(c -> c.active() && c.kind() == channelKind)
                .findFirst()
                .orElseThrow(() -> new ApiErrors.UnprocessableException(
                        "store has no active " + channelKind + " channel"));

        RegionView region = regionHint
                .flatMap(this::region)
                .filter(r -> r.store().equals(store))
                .orElseGet(() -> regionsOf(store).stream().findFirst()
                        .orElseThrow(() -> new ApiErrors.UnprocessableException(
                                "store has no region configured")));
        return new SellingContext(channel, region);
    }

    @Override
    @Transactional(readOnly = true)
    public SellingContext resolveByIds(StoreId store, ChannelId channelId, RegionId regionId) {
        ChannelView channel = channels.findById(channelId.value())
                .filter(c -> c.getStoreId().equals(store.value()))
                .map(DefaultSellingContextService::toView)
                .orElseThrow(() -> new ApiErrors.NotFoundException("channel", channelId.value()));
        RegionView region = region(regionId)
                .filter(r -> r.store().equals(store))
                .orElseThrow(() -> new ApiErrors.NotFoundException("region", regionId.value()));
        return new SellingContext(channel, region);
    }

    /** v1 seeding (§6.1): one WEB_CHAT channel + one region from the store currency. */
    @Transactional
    public void seedDefaults(StoreId store, String currency) {
        addChannel(store, ChannelView.Kind.WEB_CHAT, "Web chat");
        addRegion(store, "Default", currency, TaxPolicy.none(),
                List.of(TenderKind.PSP, TenderKind.COD, TenderKind.MANUAL, TenderKind.STORE_CREDIT));
    }

    private void requireStore(StoreId store) {
        stores.findById(store.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("store", store.value()));
    }

    private static ChannelView toView(ChannelEntity e) {
        return new ChannelView(new ChannelId(e.getId()), new StoreId(e.getStoreId()),
                ChannelView.Kind.valueOf(e.getKind()), e.getLabel(), e.isActive());
    }

    private RegionView toView(RegionEntity e) {
        return new RegionView(new RegionId(e.getId()), new StoreId(e.getStoreId()),
                e.getName(), e.getCurrency(),
                read(e.getTaxJson(), TaxPolicy.class),
                readList(e.getTendersJson()),
                e.getZoneIdsJson() == null ? List.of() : readStrings(e.getZoneIdsJson()),
                e.getPriceListId() == null ? null : new PriceListId(e.getPriceListId()));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable region field", e);
        }
    }

    private <T> T read(String raw, Class<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt region json", e);
        }
    }

    private List<TenderKind> readList(String raw) {
        try {
            return json.readValue(raw, new TypeReference<List<TenderKind>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt tenders json", e);
        }
    }

    private List<String> readStrings(String raw) {
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt zone ids json", e);
        }
    }
}
