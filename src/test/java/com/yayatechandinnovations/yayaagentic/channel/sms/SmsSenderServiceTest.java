package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.config.SecretCipher;
import com.yayatechandinnovations.yayaagentic.persistence.SmsCampaignEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SmsCampaignRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SmsSenderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SmsSenderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Guards on the SMS sender lifecycle — chiefly the §5.2 compliance gate that
 * keeps a sender out of ACTIVE until its campaign is APPROVED.
 */
@ExtendWith(MockitoExtension.class)
class SmsSenderServiceTest {

    private static final TenantId TENANT = new TenantId("t_acme");

    @Mock SmsSenderRepository senders;
    @Mock SmsCampaignRepository campaigns;

    SmsSenderService svc;

    @BeforeEach
    void setUp() {
        // a SecretCipher that round-trips raw bytes (bypasses real crypto so
        // fixtures can use plain webhook-secret bytes)
        SecretCipher cipher = new SecretCipher("", "") {
            @Override public byte[] encrypt(String p) { return p == null ? null : p.getBytes(StandardCharsets.UTF_8); }
            @Override public String decrypt(byte[] c) { return c == null ? null : new String(c, StandardCharsets.UTF_8); }
        };
        // findAndRegisterModules() pulls in JSR-310 so Instant fields on
        // SmsOnboardingState serialize, matching the app's configured mapper.
        svc = new SmsSenderService(senders, campaigns, new DevNoopSmsNumberProvisioner(),
                cipher, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void us_sender_requires_a_campaign_at_create() {
        assertThatThrownBy(() -> svc.createDraft(TENANT, new SmsSenderService.CreateSenderCommand(
                "+15551230000", SmsMarket.US, SenderKind.LONG_CODE, Acquisition.PURCHASED,
                null, Optional.empty())))
                .isInstanceOf(AdminApiException.class)
                .hasMessageContaining("campaign");
    }

    @Test
    void owned_number_porting_is_rejected_in_v1() {
        assertThatThrownBy(() -> svc.createDraft(TENANT, new SmsSenderService.CreateSenderCommand(
                "+15551230000", SmsMarket.US, SenderKind.LONG_CODE, Acquisition.HOSTED,
                new SmsCampaignId("cmp_1"), Optional.empty())))
                .isInstanceOf(AdminApiException.class)
                .hasMessageContaining("porting");
    }

    @Test
    void dr_sender_cannot_submit_without_a_bundle() {
        SmsSenderEntity e = drSender(SmsSenderStatus.DRAFT, null);
        when(senders.findByTenantIdAndId(TENANT.value(), "sms_1")).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> svc.submit(TENANT, new SmsSenderId("sms_1")))
                .isInstanceOf(AdminApiException.class)
                .hasMessageContaining("bundle");
    }

    @Test
    void pending_sender_stays_pending_until_campaign_is_approved() {
        SmsSenderEntity e = usSender(SmsSenderStatus.PENDING_REVIEW);
        when(senders.findByTenantIdAndId(TENANT.value(), "sms_1")).thenReturn(Optional.of(e));
        when(campaigns.findById("cmp_1")).thenReturn(Optional.of(campaign(CampaignStatus.PENDING_REVIEW)));
        when(senders.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmsSender result = svc.syncOne(svc.toDomain(e));

        assertThat(result.status()).isEqualTo(SmsSenderStatus.PENDING_REVIEW);  // gate held
    }

    @Test
    void pending_sender_goes_active_once_campaign_is_approved() {
        SmsSenderEntity e = usSender(SmsSenderStatus.PENDING_REVIEW);
        when(senders.findByTenantIdAndId(TENANT.value(), "sms_1")).thenReturn(Optional.of(e));
        when(campaigns.findById("cmp_1")).thenReturn(Optional.of(campaign(CampaignStatus.APPROVED)));
        when(senders.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmsSender result = svc.syncOne(svc.toDomain(e));

        assertThat(result.status()).isEqualTo(SmsSenderStatus.ACTIVE);          // gate opened
    }

    /* ---- fixtures -------------------------------------------------------- */

    private SmsSenderEntity usSender(SmsSenderStatus status) {
        SmsSenderEntity e = new SmsSenderEntity("sms_1", TENANT.value(), "+15551230000",
                SmsMarket.US.name(), status.name(), "secret".getBytes(StandardCharsets.UTF_8));
        e.setCampaignId("cmp_1");
        e.setTwilioSenderSid("PNdevx");
        e.setOnboardingStateJson("{\"twilioSenderSid\":\"PNdevx\",\"pollAttempts\":0}");
        return e;
    }

    private SmsSenderEntity drSender(SmsSenderStatus status, String bundle) {
        SmsSenderEntity e = new SmsSenderEntity("sms_1", TENANT.value(), "+18091230000",
                SmsMarket.DO.name(), status.name(), "secret".getBytes(StandardCharsets.UTF_8));
        e.setRegulatoryBundleSid(bundle);
        e.setOnboardingStateJson("{\"pollAttempts\":0}");
        return e;
    }

    private SmsCampaignEntity campaign(CampaignStatus status) {
        SmsCampaignEntity c = new SmsCampaignEntity("cmp_1", TENANT.value(), "brd_1",
                A2pUseCase.MARKETING.name(), "desc", "[\"a\",\"b\"]",
                "{\"description\":\"opt\",\"proofUrl\":null}", false, status.name());
        return c;
    }
}
