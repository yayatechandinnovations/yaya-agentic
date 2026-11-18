package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.InteractivePayload;
import com.yayatechandinnovations.yayaagentic.channel.InteractiveRenderer;
import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.EngineSessionCloser;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The greeting's offer to continue the app conversation, and the channel close that ends the engine session. */
class HandoffTapTest {

    @Test
    void the_offer_renders_as_two_buttons_whose_payloads_survive_the_round_trip() {
        TurnEvent.UiHint offer = InboundDispatchWorker.handoffOfferHint(
                new Ids.SessionId("0b1f6d7e-9d2a-4b7f-8c1d-2e3f4a5b6c7d"));
        var payload = new DefaultInteractiveRenderer().render("Hola.", offer,
                new InteractiveRenderer.RenderContext(new ChannelKind.WhatsApp(), null));

        assertThat(payload).isInstanceOf(InteractivePayload.Buttons.class);
        var buttons = (InteractivePayload.Buttons) payload;
        assertThat(buttons.buttons()).extracting(InteractivePayload.Button::id)
                .containsExactly("opt:handoff:0b1f6d7e-9d2a-4b7f-8c1d-2e3f4a5b6c7d", "opt:fresh");
        assertThat(buttons.buttons()).extracting(InteractivePayload.Button::title)
                .containsExactly("Seguir con la app", "Empezar de cero");
        assertThat(offer.payload().get("source")).isEqualTo("start");
        assertThat(((Map<?, ?>) ((java.util.List<?>) offer.payload().get("options")).get(0)).get("send"))
                .isEqualTo("Seguir con la app");

        assertThat(InboundDispatchWorker.handoffTapOf("handoff:0b1f6d7e-9d2a-4b7f-8c1d-2e3f4a5b6c7d", "button"))
                .isEqualTo("handoff:0b1f6d7e-9d2a-4b7f-8c1d-2e3f4a5b6c7d");
        assertThat(InboundDispatchWorker.handoffTapOf("fresh", "button")).isEqualTo("fresh");
        assertThat(InboundDispatchWorker.handoffTapOf("opt:handoff:0b1f6d7e-9d2a-4b7f-8c1d-2e3f4a5b6c7d", "button"))
                .isEqualTo("handoff:0b1f6d7e-9d2a-4b7f-8c1d-2e3f4a5b6c7d");
        assertThat(InboundDispatchWorker.handoffTapOf("opt:fresh", "button")).isEqualTo("fresh");
        assertThat(InboundDispatchWorker.handoffTapOf("handoff:", "button")).isNull();
        assertThat(InboundDispatchWorker.handoffTapOf("handoff:x", "list_row")).isNull();
        assertThat(InboundDispatchWorker.handoffTapOf("act:abc", "button")).isNull();
        // Neither id is a frame answer: the engine never sees them as a selection.
        assertThat(InboundDispatchWorker.selectionFor("handoff:x", "button")).isNull();
        assertThat(InboundDispatchWorker.selectionFor("fresh", "button")).isNull();
        assertThat(InboundDispatchWorker.withOfferLine("Hola, soy Yaya."))
                .isEqualTo("Hola, soy Yaya.\n\n¿Seguimos con lo que estabas haciendo en la app?");
    }

    @Test
    void closing_a_channel_row_ends_the_engine_session_behind_it() {
        ChannelSessionRepository repo = mock(ChannelSessionRepository.class);
        EngineSessionCloser closer = mock(EngineSessionCloser.class);
        ChannelSessionService service = new ChannelSessionService(repo);
        service.setEngineSessions(closer);
        ChannelSessionEntity row = new ChannelSessionEntity("t", "snd", "+1555", "11111111-2222-3333-4444-555555555555",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
        when(repo.findByTenantIdAndSessionId("t", "11111111-2222-3333-4444-555555555555"))
                .thenReturn(Optional.of(row));

        service.close(new Ids.TenantId("t"), new Ids.SessionId("11111111-2222-3333-4444-555555555555"), "USER_REQUESTED_NEW");

        ArgumentCaptor<Ids.SessionId> ended = ArgumentCaptor.forClass(Ids.SessionId.class);
        verify(closer).endIfOpen(ended.capture(), eq("USER_REQUESTED_NEW"));
        assertThat(ended.getValue().value()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(row.getStatus()).isEqualTo("CLOSED");

        // Already closed: nothing to end again.
        service.close(new Ids.TenantId("t"), new Ids.SessionId("11111111-2222-3333-4444-555555555555"), "IDLE_TIMEOUT");
        verify(closer, never()).endIfOpen(any(), eq("IDLE_TIMEOUT"));
    }

    @Test
    void the_idle_timeout_close_in_open_or_reuse_ends_the_engine_session_too() {
        ChannelSessionRepository repo = mock(ChannelSessionRepository.class);
        EngineSessionCloser closer = mock(EngineSessionCloser.class);
        ChannelSessionService service = new ChannelSessionService(repo);
        service.setEngineSessions(closer);
        ChannelSessionEntity row = new ChannelSessionEntity("t", "snd", "+1555", "11111111-2222-3333-4444-555555555555",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
        when(repo.findFirstByTenantIdAndSenderIdAndEndUserPhoneAndStatus("t", "snd", "+1555", "OPEN"))
                .thenReturn(Optional.of(row));

        var outcome = service.openOrReuse(new Ids.TenantId("t"), new SenderId("snd"), "+1555", "hola",
                com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy.defaults());

        assertThat(outcome).isInstanceOf(ChannelSessionService.Outcome.Reopen.class);
        verify(closer).endIfOpen(any(), eq("IDLE_TIMEOUT"));
    }
}
