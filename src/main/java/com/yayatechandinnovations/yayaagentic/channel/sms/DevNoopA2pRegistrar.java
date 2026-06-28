package com.yayatechandinnovations.yayaagentic.channel.sms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Dev / no-Twilio {@link A2pRegistrar}. Accepts every submission and, on the
 * next poll, advances Brands to {@code VERIFIED} and Campaigns to
 * {@code APPROVED} — so the whole compliance flow is exercisable end-to-end on
 * a laptop with no carrier account.
 *
 * <p>Annotated {@link ConditionalOnMissingBean} so the real
 * {@code TwilioA2pRegistrar}, once it lands, takes over simply by existing. The
 * synthetic SIDs it mints are clearly fake ({@code BUdev…}, {@code CMdev…}) so
 * they can never be mistaken for live carrier identifiers in logs or audits.
 */
@Component
@ConditionalOnMissingBean(name = "twilioA2pRegistrar")
public class DevNoopA2pRegistrar implements A2pRegistrar {

    @Override
    public boolean isReady() {
        return true;   // dev registrar is always "ready" — that's the point
    }

    @Override
    public A2pSubmitResult submitBrand(SmsBrand brand) {
        return new A2pSubmitResult.Accepted("BUdev" + compact(brand.id().value()), null, "PENDING");
    }

    @Override
    public A2pSubmitResult submitCampaign(SmsCampaign campaign, SmsBrand brand) {
        String compact = compact(campaign.id().value());
        return new A2pSubmitResult.Accepted("CMdev" + compact, "MGdev" + compact, "PENDING");
    }

    @Override
    public BrandPoll pollBrand(SmsBrand brand) {
        return new BrandPoll(BrandStatus.VERIFIED, "BNdev" + compact(brand.id().value()), null, null);
    }

    @Override
    public CampaignPoll pollCampaign(SmsCampaign campaign) {
        return new CampaignPoll(CampaignStatus.APPROVED, null, null);
    }

    private static String compact(String id) {
        return id.replaceAll("[^A-Za-z0-9]", "");
    }
}
