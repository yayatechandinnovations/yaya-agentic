package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.ConversationHoldService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ConversationHoldEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The takeover edge: the gate runs first, the phone is required, and the transcript is what the customer wrote. */
class SystemConversationHoldControllerTest {

    static final String TENANT = "yaya";
    static final String SENDER = "palpatio-system";
    static final String PHONE = "+18095551234";

    private final IntegrationSenderGate gate = mock(IntegrationSenderGate.class);
    private final ConversationHoldService holds = mock(ConversationHoldService.class);
    private final InboundMessageRepository inbound = mock(InboundMessageRepository.class);
    private final SystemConversationHoldController controller =
            new SystemConversationHoldController(gate, holds, inbound);

    @Test
    void holding_passes_the_phone_and_the_duration_through() {
        OffsetDateTime until = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
        when(holds.hold(any(), eq(PHONE), eq(Duration.ofMinutes(60)), eq("handoff")))
                .thenReturn(new ConversationHoldEntity(TENANT, PHONE, until, "handoff"));

        var response = controller.hold(TENANT, SENDER,
                new SystemConversationHoldController.HoldRequest(PHONE, 60, "handoff"), exchange()).block();

        assertThat(response).isNotNull();
        assertThat(response.phone()).isEqualTo(PHONE);
        assertThat(response.heldUntil()).isEqualTo(until.toInstant());
        verify(gate).admit(any(), any(), any());
    }

    @Test
    void releasing_gives_the_conversation_back() {
        var response = controller.release(TENANT, SENDER,
                new SystemConversationHoldController.HoldRequest(PHONE, null, null), exchange()).block();

        assertThat(response).isNotNull();
        assertThat(response.heldUntil()).isNull();
        verify(holds).release(new Ids.TenantId(TENANT), PHONE);
    }

    @Test
    void a_request_without_a_phone_is_a_bad_request() {
        assertThatThrownBy(() -> controller.hold(TENANT, SENDER,
                new SystemConversationHoldController.HoldRequest("  ", 10, null), exchange()).block())
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void the_transcript_reads_from_when_the_hold_started_and_skips_empty_bodies() {
        OffsetDateTime heldFrom = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30);
        ConversationHoldEntity hold = held(heldFrom);
        when(holds.find(any(), eq(PHONE))).thenReturn(Optional.of(hold));
        when(inbound.findTop50ByTenantIdAndEndUserPhoneAndReceivedAtAfterOrderByReceivedAtAsc(
                eq(TENANT), eq(PHONE), eq(heldFrom)))
                .thenReturn(List.of(inboundRow("¿Alguien me puede ayudar?"), inboundRow("  "), inboundRow(null)));

        var response = controller.messages(TENANT, SENDER, PHONE, null, exchange()).block();

        assertThat(response).isNotNull();
        assertThat(response.held()).isTrue();
        assertThat(response.messages()).singleElement()
                .extracting(SystemConversationHoldController.HeldMessage::text)
                .isEqualTo("¿Alguien me puede ayudar?");
    }

    @Test
    void a_malformed_since_is_a_bad_request() {
        assertThatThrownBy(() -> controller.messages(TENANT, SENDER, PHONE, "yesterday", exchange()).block())
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** A hold entity with {@code held_from} set, which the database normally fills. */
    private static ConversationHoldEntity held(OffsetDateTime from) {
        ConversationHoldEntity hold = new ConversationHoldEntity(TENANT, PHONE,
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1), "handoff");
        set(hold, "heldFrom", from);
        return hold;
    }

    private static InboundMessageEntity inboundRow(String body) {
        InboundMessageEntity row = new InboundMessageEntity("SM" + System.nanoTime(), TENANT, "wa-1", PHONE, 0, "{}");
        row.setBodyRedacted(body);
        set(row, "receivedAt", OffsetDateTime.now(ZoneOffset.UTC));
        return row;
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/v1/integrations/" + TENANT
                + "/senders/" + SENDER + "/holds"));
    }
}
