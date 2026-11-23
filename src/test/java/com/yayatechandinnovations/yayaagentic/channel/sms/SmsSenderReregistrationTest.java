package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V98 — a revoked SMS number keeps its row as history but can be registered
 * again, in any workspace; a live one still can't be double-booked.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class SmsSenderReregistrationTest {

    @Autowired SmsSenderService senders;
    @Autowired TenantRepository tenants;

    @Test
    void a_revoked_number_registers_again_elsewhere() {
        TenantId first = tenant();
        TenantId second = tenant();
        String phone = "+1809" + String.format("%07d", (long) (Math.random() * 10_000_000L));

        SmsSender original = senders.createDraft(first, draft(phone));
        assertThatThrownBy(() -> senders.createDraft(second, draft(phone)))
                .isInstanceOfSatisfying(AdminApiException.class,
                        e -> assertThat(e.code()).isEqualTo("phone_in_use"));

        senders.revoke(first, original.id());

        SmsSender again = senders.createDraft(second, draft(phone));
        assertThat(again.id()).isNotEqualTo(original.id());
        assertThat(again.tenant()).isEqualTo(second);
    }

    private static SmsSenderService.CreateSenderCommand draft(String phone) {
        return new SmsSenderService.CreateSenderCommand(phone, SmsMarket.DO, SenderKind.LONG_CODE,
                Acquisition.PURCHASED, null, Optional.empty());
    }

    private TenantId tenant() {
        String id = "sms-rereg-" + UUID.randomUUID().toString().substring(0, 8);
        tenants.save(new TenantEntity(id, "SMS re-registration " + id));
        return new TenantId(id);
    }
}
