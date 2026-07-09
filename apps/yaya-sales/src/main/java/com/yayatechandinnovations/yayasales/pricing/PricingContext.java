package com.yayatechandinnovations.yayasales.pricing;

import com.yayatechandinnovations.yayasales.platform.Ids.ChannelId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.stores.TaxPolicy;

/**
 * §6.2 — the selling context the quote is computed in. {@code shipping} is the
 * chosen delivery option's price (S5's quote engine feeds it; zero until then).
 */
public record PricingContext(
        RegionId region,
        ChannelId channel,
        String currency,
        TaxPolicy tax,
        String customerGroup,
        String couponCode,
        Money shipping) {}
