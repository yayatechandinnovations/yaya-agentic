package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.operator_auth.config.SecretCipher;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SmsCampaignRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SmsSenderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SmsSenderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the outbound dispatcher buffers the engine flux into one message and
 * records segments. Builds a real {@link SmsSenderService} over mocked repo
 * <em>interfaces</em> — Mockito on Java 25 can't subclass the concrete service,
 * but interface mocks work fine.
 */
@ExtendWith(MockitoExtension.class)
class SmsOutboundDispatcherTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("t_acme");
    private static final SmsSenderId SENDER = new SmsSenderId("sms_1");

    @Mock SmsSenderRepository senderRepo;
    @Mock SmsCampaignRepository campaignRepo;
    @Mock OutboundMessageRepository outbound;

    CapturingTransport transport;
    SmsOutboundDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        SecretCipher cipher = new SecretCipher("", "") {
            @Override public byte[] encrypt(String p) { return p == null ? null : p.getBytes(StandardCharsets.UTF_8); }
            @Override public String decrypt(byte[] c) { return c == null ? null : new String(c, StandardCharsets.UTF_8); }
        };
        SmsSenderService senders = new SmsSenderService(
                senderRepo, campaignRepo, new DevNoopSmsNumberProvisioner(),
                cipher, new ObjectMapper().findAndRegisterModules());
        transport = new CapturingTransport();
        dispatcher = new SmsOutboundDispatcher(senders, transport, outbound);

        lenient().when(senderRepo.findByTenantIdAndId(TENANT.value(), SENDER.value()))
                .thenReturn(Optional.of(usSenderEntity()));
        lenient().when(outbound.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void a_plan_is_its_list_followed_by_the_reply_instruction() {
        String text = "Voy a hacer esto, en orden:\n1. Detener la venta en la estación 12\n"
                + "2. Enviar el aviso «Cierre» a la estación 07\n¿Lo hago?";
        dispatcher.dispatchTurn(TENANT, SENDER, "+18095551234", new Ids.SessionId("sess_1"),
                Flux.just(new TurnEvent.UiHint("plan", java.util.Map.of("callId", "plan-1")),
                        new TurnEvent.Token(text)));
        // Words, not «SÍ o NO» alone: a step can be chosen, and «1» is a step.
        assertThat(transport.last.bodyText()).isEqualTo(text + " Responde TODO para hacerlos todos, CANCELAR "
                + "para no hacer nada, o dime cuáles pasos quieres (por ejemplo «solo la 1»).");

        // The run's progress adds nothing to the model's account.
        dispatcher.dispatchTurn(TENANT, SENDER, "+18095551234", new Ids.SessionId("sess_1"),
                Flux.just(new TurnEvent.UiHint("plan_step", java.util.Map.of("callId", "plan-1")),
                        new TurnEvent.UiHint("plan_result", java.util.Map.of("callId", "plan-1")),
                        new TurnEvent.Token("Hecho.")));
        assertThat(transport.last.bodyText()).isEqualTo("Hecho.");
    }

    @Test
    void buffers_token_stream_into_one_message_and_records_segments() {
        dispatcher.dispatchTurn(TENANT, SENDER, "+18095551234", new Ids.SessionId("sess_1"),
                Flux.just(new TurnEvent.Token("Hello "), new TurnEvent.Token("world"),
                        new TurnEvent.Token("!")));

        assertThat(transport.last).isNotNull();
        assertThat(transport.last.bodyText()).isEqualTo("Hello world!");
        assertThat(transport.last.fromPhone()).isEqualTo("+15551112222");
        assertThat(transport.last.toPhone()).isEqualTo("+18095551234");

        ArgumentCaptor<OutboundMessageEntity> saved = ArgumentCaptor.forClass(OutboundMessageEntity.class);
        verify(outbound).save(saved.capture());
        assertThat(saved.getValue().getSegmentCount()).isEqualTo(1);
        assertThat(saved.getValue().getStatus()).isEqualTo("SENT");
    }

    @Test
    void greeting_appends_quick_replies_as_numbered_lines() {
        dispatcher.dispatchGreeting(TENANT, SENDER, "+18095551234", new Ids.SessionId("sess_1"),
                "Welcome!", List.of("Track order", "Talk to agent"));

        assertThat(transport.last.bodyText())
                .contains("Welcome!").contains("1) Track order").contains("2) Talk to agent");
    }

    @Test
    void blank_body_sends_nothing() {
        dispatcher.dispatchTurn(TENANT, SENDER, "+18095551234", new Ids.SessionId("sess_1"),
                Flux.just(new TurnEvent.Token("")));
        assertThat(transport.last).isNull();
    }

    private SmsSenderEntity usSenderEntity() {
        SmsSenderEntity e = new SmsSenderEntity(SENDER.value(), TENANT.value(), "+15551112222",
                SmsMarket.US.name(), SmsSenderStatus.ACTIVE.name(),
                "secret".getBytes(StandardCharsets.UTF_8));
        e.setCampaignId("cmp_1");
        return e;
    }

    private static final class CapturingTransport implements SmsTransport {
        SmsMessage last;
        @Override public String name() { return "capturing"; }
        @Override public void send(SmsMessage msg) { this.last = msg; }
    }
}
