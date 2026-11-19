package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.outreach.OutreachService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Wire shapes of ticket-share design §8 P2/P3/P6 on the ingest DTOs. */
class SystemInboundDtosDeliveryTest {

    @Test
    void fallback_defaults_to_chat_and_parses_none_case_insensitively() {
        var legacy = new SystemInboundDtos.IngestRequest(null, "hi", null, null, "k", Map.of(), null,
                new SystemInboundDtos.DeliveryRef("WHATSAPP", "+18095551234"), null);
        assertThat(legacy.deliveryDomain().fallback()).isEqualTo(DeliveryRequest.Fallback.CHAT);
        assertThat(legacy.deliveryDomain().wantsChatFallback()).isTrue();

        var none = new SystemInboundDtos.IngestRequest(null, "hi", null, null, "k", Map.of(), null,
                new SystemInboundDtos.DeliveryRef("whatsapp", "+18095551234", "none"), null);
        assertThat(none.deliveryDomain().fallback()).isEqualTo(DeliveryRequest.Fallback.NONE);
        assertThat(none.deliveryDomain().wantsChatFallback()).isFalse();

        var bad = new SystemInboundDtos.IngestRequest(null, "hi", null, null, "k", Map.of(), null,
                new SystemInboundDtos.DeliveryRef("WHATSAPP", "+18095551234", "EMAIL"), null);
        assertThatThrownBy(bad::deliveryDomain).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chat_requests_never_count_as_no_fallback() {
        var chat = new DeliveryRequest(DeliveryRequest.Channel.CHAT, null, null, DeliveryRequest.Fallback.NONE);
        assertThat(chat.wantsChatFallback()).isTrue();
    }

    @Test
    void receipt_carries_template_used_and_delivered_via_is_null_when_every_leg_failed() {
        Instant now = Instant.now();
        var failed = new OutreachService.OutreachReceipt(new Ids.OutreachId("o-1"),
                OutreachService.Disposition.DROPPED, List.of(), now,
                List.of(new OutreachService.Delivery("WHATSAPP", "opted_out", null, "FAILED", now, null)));
        var view = SystemInboundDtos.IngestResponse.of(failed);
        assertThat(view.disposition()).isEqualTo("DROPPED");
        assertThat(view.deliveredVia()).isNull();
        assertThat(view.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo("FAILED");
            assertThat(d.fallbackReason()).isEqualTo("opted_out");
            assertThat(d.templateUsed()).isNull();
            assertThat(d.errorCode()).isNull();
        });

        // A provider rejection carries its code on the same row, on the receipt and on the status GET alike.
        var rejected = new OutreachService.Delivery("WHATSAPP", "send_failed", null, "FAILED", now, null, "63016");
        assertThat(SystemInboundDtos.DeliveryView.of(rejected).errorCode()).isEqualTo("63016");
        var statusView = new SystemInboundDtos.StatusResponse("o-3", "DROPPED", now,
                List.of(SystemInboundDtos.DeliveryView.of(rejected)));
        assertThat(statusView.deliveries().get(0).errorCode()).isEqualTo("63016");
        assertThat(statusView.deliveries().get(0).fallbackReason()).isEqualTo("send_failed");

        var sent = new OutreachService.OutreachReceipt(new Ids.OutreachId("o-2"),
                OutreachService.Disposition.DELIVERED, List.of(), now,
                List.of(new OutreachService.Delivery("WHATSAPP", null, "SM1", "SENT", now, "sl_ticket_copy_v1")));
        var sentView = SystemInboundDtos.IngestResponse.of(sent);
        assertThat(sentView.deliveredVia()).isEqualTo("WHATSAPP");
        assertThat(sentView.deliveries().get(0).templateUsed()).isEqualTo("sl_ticket_copy_v1");
        assertThat(sentView.deliveries().get(0).errorCode()).isNull();
    }
}
