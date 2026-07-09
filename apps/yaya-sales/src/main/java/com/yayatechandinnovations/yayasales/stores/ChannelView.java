package com.yayatechandinnovations.yayasales.stores;

import com.yayatechandinnovations.yayasales.platform.Ids.ChannelId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

public record ChannelView(ChannelId id, StoreId store, Kind kind, String label, boolean active) {

    public enum Kind { WEB_CHAT, WHATSAPP, SMS, POS }
}
