package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.persistence.SmsBrandEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SmsBrandRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SmsCampaignEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SmsCampaignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Guards on the A2P compliance flow — the rules that protect the platform's
 * shared sending reputation. These are the parts of {@code sms-channel-design.md}
 * §6.2 / §6.3 that cannot be expressed by the enum state machines alone.
 */
@ExtendWith(MockitoExtension.class)
class SmsComplianceServiceTest {

    private static final TenantId TENANT = new TenantId("t_acme");

    @Mock SmsBrandRepository brands;
    @Mock SmsCampaignRepository campaigns;

    SmsComplianceService svc;

    @BeforeEach
    void setUp() {
        svc = new SmsComplianceService(brands, campaigns, new DevNoopA2pRegistrar(), new ObjectMapper());
    }

    @Test
    void createBrand_rejects_a_second_brand_for_the_same_tenant() {
        when(brands.findByTenantId(TENANT.value())).thenReturn(List.of(brandEntity(BrandStatus.DRAFT)));

        assertThatThrownBy(() -> svc.createBrand(TENANT, business()))
                .isInstanceOf(AdminApiException.class)
                .hasMessageContaining("brand");
    }

    @Test
    void createBrand_happy_path_starts_in_draft() {
        when(brands.findByTenantId(TENANT.value())).thenReturn(List.of());
        when(brands.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmsBrand brand = svc.createBrand(TENANT, business());

        assertThat(brand.status()).isEqualTo(BrandStatus.DRAFT);
        assertThat(brand.business().legalName()).isEqualTo("Acme Co");
    }

    @Test
    void createCampaign_marks_restricted_use_case_for_operator_review() {
        when(brands.findByTenantIdAndId(TENANT.value(), "brd_1"))
                .thenReturn(Optional.of(brandEntity(BrandStatus.VERIFIED)));
        when(campaigns.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmsCampaign c = svc.createCampaign(TENANT, new SmsComplianceService.CreateCampaignCommand(
                new SmsBrandId("brd_1"), A2pUseCase.GAMBLING, "lottery results",
                List.of("Your numbers are in", "Draw closes at 8pm"),
                new OptInDescription("web signup", null), null));

        assertThat(c.requiresOperatorReview()).isTrue();
        assertThat(c.status()).isEqualTo(CampaignStatus.DRAFT);
    }

    @Test
    void createCampaign_standard_use_case_does_not_require_review() {
        when(brands.findByTenantIdAndId(TENANT.value(), "brd_1"))
                .thenReturn(Optional.of(brandEntity(BrandStatus.VERIFIED)));
        when(campaigns.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmsCampaign c = svc.createCampaign(TENANT, new SmsComplianceService.CreateCampaignCommand(
                new SmsBrandId("brd_1"), A2pUseCase.ACCOUNT_NOTIFICATION, "order updates",
                List.of("Your order shipped", "Out for delivery"),
                new OptInDescription("checkout opt-in", null), null));

        assertThat(c.requiresOperatorReview()).isFalse();
    }

    @Test
    void submitCampaign_blocked_until_brand_is_verified() {
        when(campaigns.findByTenantIdAndId(TENANT.value(), "cmp_1"))
                .thenReturn(Optional.of(campaignEntity(A2pUseCase.MARKETING, false, false)));
        when(brands.findById("brd_1")).thenReturn(Optional.of(brandEntity(BrandStatus.PENDING_VERIFICATION)));

        assertThatThrownBy(() -> svc.submitCampaign(TENANT, new SmsCampaignId("cmp_1")))
                .isInstanceOf(AdminApiException.class)
                .hasMessageContaining("VERIFIED");
    }

    @Test
    void submitCampaign_blocked_while_operator_review_is_pending() {
        when(campaigns.findByTenantIdAndId(TENANT.value(), "cmp_1"))
                .thenReturn(Optional.of(campaignEntity(A2pUseCase.GAMBLING, true, true)));
        when(brands.findById("brd_1")).thenReturn(Optional.of(brandEntity(BrandStatus.VERIFIED)));

        assertThatThrownBy(() -> svc.submitCampaign(TENANT, new SmsCampaignId("cmp_1")))
                .isInstanceOf(AdminApiException.class)
                .hasMessageContaining("review");
    }

    @Test
    void submitCampaign_restricted_requires_evidence_even_after_review_release() {
        // operator released review (requiresReview=false) but no evidence attached
        when(campaigns.findByTenantIdAndId(TENANT.value(), "cmp_1"))
                .thenReturn(Optional.of(campaignEntity(A2pUseCase.GAMBLING, false, false)));
        when(brands.findById("brd_1")).thenReturn(Optional.of(brandEntity(BrandStatus.VERIFIED)));

        assertThatThrownBy(() -> svc.submitCampaign(TENANT, new SmsCampaignId("cmp_1")))
                .isInstanceOf(AdminApiException.class)
                .hasMessageContaining("evidence");
    }

    @Test
    void submitCampaign_standard_use_case_reaches_pending_review() {
        when(campaigns.findByTenantIdAndId(TENANT.value(), "cmp_1"))
                .thenReturn(Optional.of(campaignEntity(A2pUseCase.MARKETING, false, false)));
        when(brands.findById("brd_1")).thenReturn(Optional.of(brandEntity(BrandStatus.VERIFIED)));
        when(campaigns.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmsCampaign c = svc.submitCampaign(TENANT, new SmsCampaignId("cmp_1"));

        // DevNoopA2pRegistrar accepts → PENDING_REVIEW + a synthetic SID
        assertThat(c.status()).isEqualTo(CampaignStatus.PENDING_REVIEW);
        assertThat(c.twilioCampaignSid()).startsWith("CMdev");
        assertThat(c.messagingServiceSid()).startsWith("MGdev");
    }

    @Test
    void syncBrand_advances_pending_to_verified() {
        SmsBrandEntity e = brandEntity(BrandStatus.PENDING_VERIFICATION);
        when(brands.findById("brd_1")).thenReturn(Optional.of(e));
        when(brands.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmsBrand result = svc.syncBrand(svc.toBrand(e));

        assertThat(result.status()).isEqualTo(BrandStatus.VERIFIED);
        assertThat(result.tcrBrandId()).startsWith("BNdev");   // DevNoopA2pRegistrar verdict
    }

    @Test
    void syncCampaign_advances_pending_to_approved() {
        SmsCampaignEntity e = campaignEntity(A2pUseCase.MARKETING, false, false);
        e.setStatus(CampaignStatus.PENDING_REVIEW.name());
        e.setTwilioCampaignSid("CMdevx");
        when(campaigns.findById("cmp_1")).thenReturn(Optional.of(e));
        when(campaigns.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmsCampaign result = svc.syncCampaign(svc.toCampaign(e));

        assertThat(result.status()).isEqualTo(CampaignStatus.APPROVED);
    }

    /* ---- fixtures -------------------------------------------------------- */

    private SmsBrandEntity brandEntity(BrandStatus status) {
        SmsBrandEntity e = new SmsBrandEntity("brd_1", TENANT.value(), writeBusiness(), status.name());
        e.setTwilioProfileSid("BUdevx");
        return e;
    }

    private SmsCampaignEntity campaignEntity(A2pUseCase useCase, boolean requiresReview, boolean withEvidenceNull) {
        SmsCampaignEntity e = new SmsCampaignEntity(
                "cmp_1", TENANT.value(), "brd_1", useCase.name(), "desc",
                "[\"sample one\",\"sample two\"]",
                "{\"description\":\"web opt-in\",\"proofUrl\":null}",
                requiresReview, CampaignStatus.DRAFT.name());
        // withEvidenceNull stays the default (no evidence) — restricted-evidence test relies on it
        return e;
    }

    private BusinessIdentity business() {
        return new BusinessIdentity("Acme Co", "12-3456789", "1 Main St", "RETAIL",
                "https://acme.example", "ops@acme.example");
    }

    private String writeBusiness() {
        try {
            return new ObjectMapper().writeValueAsString(business());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
