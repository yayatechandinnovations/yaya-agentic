package com.yayatechandinnovations.yayaagentic.outreach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.system.AddressingPolicy;
import com.yayatechandinnovations.yayaagentic.channel.system.Correlation;
import com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest;
import com.yayatechandinnovations.yayaagentic.channel.system.MediationMode;
import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.channel.system.SenderTrust;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemInboundMessage;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppOutreachLeg;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.outreach.resolvers.ByPrincipalRecipientResolver;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChatInboxRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachSessionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SystemInboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SystemInboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.recorder.RecorderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * pos-ticket-whatsapp-share-design.md §8 P2/P6: {@code delivery.fallback: NONE}
 * never writes a chat — a WhatsApp leg that cannot go out is a FAILED
 * delivery with its reason, the ledger says FAILED and the receipt DROPPED;
 * a successful leg reports the template it used.
 */
class OutreachNoFallbackTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");
    private static final Ids.SystemSenderId SENDER = new Ids.SystemSenderId("sl-owner-ops");
    private static final String PHONE = "+18095551234";

    private ChatInboxRepository chatInbox;
    private ConversationEngine engine;
    private WhatsAppOutreachLeg leg;
    private OutreachDeliveryRepository deliveries;
    private SystemInboundMessageRepository ledger;
    private DefaultOutreachService service;

    @BeforeEach
    void setUp() {
        SystemSenderService senders = mock(SystemSenderService.class);
        engine = mock(ConversationEngine.class);
        ledger = mock(SystemInboundMessageRepository.class);
        chatInbox = mock(ChatInboxRepository.class);
        leg = mock(WhatsAppOutreachLeg.class);
        deliveries = mock(OutreachDeliveryRepository.class);

        service = new DefaultOutreachService(senders, List.of(new ByPrincipalRecipientResolver()), engine,
                mock(RecorderRouter.class), mock(ChatEntryPointRepository.class), mock(OutreachSessionRepository.class),
                chatInbox, ledger, mock(MediationFramer.class), mock(MediationService.class),
                mock(com.yayatechandinnovations.yayaagentic.outreach.media.MediaLibraryService.class),
                mock(com.yayatechandinnovations.yayaagentic.outreach.push.PushNotifier.class), new ObjectMapper());
        service.setWhatsappLeg(leg);
        service.setDeliveries(deliveries);

        when(senders.find(TENANT, SENDER)).thenReturn(Optional.of(new SystemSender(SENDER, TENANT, "staff_ep",
                "Dueño", true, new SenderTrust("shared-secret-jwt", Set.of(), ""), MediationMode.VERBATIM,
                Optional.empty(), AddressingPolicy.principalsOnly(), Optional.empty(), Optional.empty(),
                Instant.now(), Instant.now(), Optional.of("wa_1"))));
        when(ledger.findByTenantIdAndSenderIdAndDedupKey(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void failed_leg_without_fallback_is_a_failed_delivery_and_a_dropped_receipt_with_no_chat() {
        when(leg.deliver(any())).thenReturn(new WhatsAppOutreachLeg.Outcome(false, "template_not_approved",
                null, null, null, null));

        var receipt = service.ingest(message(null), new OutreachService.OutreachAuth(posPrincipal()));

        verify(chatInbox, never()).save(any());
        verify(engine, never()).start(any(), any());
        assertThat(receipt.disposition()).isEqualTo(OutreachService.Disposition.DROPPED);
        assertThat(receipt.sessionIds()).isEmpty();
        assertThat(receipt.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.channel()).isEqualTo("WHATSAPP");
            assertThat(d.status()).isEqualTo("FAILED");
            assertThat(d.fallbackReason()).isEqualTo("template_not_approved");
            assertThat(d.templateUsed()).isNull();
        });

        ArgumentCaptor<SystemInboundMessageEntity> ledgerRow = ArgumentCaptor.forClass(SystemInboundMessageEntity.class);
        verify(ledger).save(ledgerRow.capture());
        assertThat(ledgerRow.getValue().getStatus()).isEqualTo("FAILED");

        ArgumentCaptor<OutreachDeliveryEntity> row = ArgumentCaptor.forClass(OutreachDeliveryEntity.class);
        verify(deliveries).save(row.capture());
        assertThat(row.getValue().getChannel()).isEqualTo("WHATSAPP");
        assertThat(row.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(row.getValue().getFallbackReason()).isEqualTo("template_not_approved");
        assertThat(row.getValue().getEndUserPhone()).isEqualTo(PHONE);
        assertThat(row.getValue().getRecipientKey()).isEqualTo("phone:" + PHONE);
    }

    @Test
    void successful_leg_reports_the_template_it_used_and_the_ledger_says_delivered() {
        when(leg.deliver(any())).thenReturn(new WhatsAppOutreachLeg.Outcome(true, null, "SM1",
                "sl_ticket_copy_v1", "Gracias por tu compra", null));

        var receipt = service.ingest(message(null), new OutreachService.OutreachAuth(posPrincipal()));

        assertThat(receipt.disposition()).isEqualTo(OutreachService.Disposition.DELIVERED);
        assertThat(receipt.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.channel()).isEqualTo("WHATSAPP");
            assertThat(d.status()).isEqualTo("SENT");
            assertThat(d.providerMessageId()).isEqualTo("SM1");
            assertThat(d.templateUsed()).isEqualTo("sl_ticket_copy_v1");
        });
        ArgumentCaptor<SystemInboundMessageEntity> ledgerRow = ArgumentCaptor.forClass(SystemInboundMessageEntity.class);
        verify(ledger).save(ledgerRow.capture());
        assertThat(ledgerRow.getValue().getStatus()).isEqualTo("DELIVERED");
        verify(chatInbox, never()).save(any());
    }

    @Test
    void status_of_a_chatless_message_reads_the_ledger_and_the_delivery_rows() {
        SystemInboundMessageEntity row = new SystemInboundMessageEntity("o-1", TENANT.value(), SENDER.value(), "k");
        row.setStatus("FAILED");
        when(ledger.findById("o-1")).thenReturn(Optional.of(row));
        OutreachDeliveryEntity d = new OutreachDeliveryEntity(TENANT.value(), "o-1", "phone:" + PHONE,
                "WHATSAPP", "FAILED");
        d.setFallbackReason("opted_out");
        when(deliveries.findByTenantIdAndOutreachIdOrderByIdAsc(TENANT.value(), "o-1")).thenReturn(List.of(d));

        var status = service.statusFor(TENANT, SENDER, new Ids.OutreachId("o-1")).orElseThrow();

        assertThat(status.status()).isEqualTo("FAILED");
        assertThat(status.deliveries()).singleElement().satisfies(v -> {
            assertThat(v.status()).isEqualTo("FAILED");
            assertThat(v.fallbackReason()).isEqualTo("opted_out");
        });
    }

    @Test
    void a_recipient_is_still_honoured_when_given_with_fallback_none() {
        when(leg.deliver(any())).thenReturn(new WhatsAppOutreachLeg.Outcome(false, "opted_out", null, null, null, null));

        var receipt = service.ingest(message(new OutreachRecipient.ByPrincipal("chat:" + PHONE)),
                new OutreachService.OutreachAuth(posPrincipal()));

        assertThat(receipt.disposition()).isEqualTo(OutreachService.Disposition.DROPPED);
        verify(chatInbox, never()).save(any());
        ArgumentCaptor<OutreachDeliveryEntity> row = ArgumentCaptor.forClass(OutreachDeliveryEntity.class);
        verify(deliveries).save(row.capture());
        assertThat(row.getValue().getRecipientKey()).isEqualTo("principal:chat:" + PHONE);
    }

    @Test
    void a_message_without_recipient_needs_fallback_none() {
        assertThatThrownBy(() -> new SystemInboundMessage(TENANT, SENDER, null, "hola", List.of(),
                new Correlation("POS", "s-1", "t-1"), "dedup-x", Map.of(), List.of(),
                new DeliveryRequest(DeliveryRequest.Channel.WHATSAPP, PHONE, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipient required");
    }

    private SystemInboundMessage message(OutreachRecipient recipient) {
        return new SystemInboundMessage(TENANT, SENDER, recipient,
                "Gracias por tu compra", List.of(), new Correlation("ticket-share", "share-1", "ticket-1"),
                "ticket-share:share-1", Map.of(), List.of(),
                new DeliveryRequest(DeliveryRequest.Channel.WHATSAPP, PHONE,
                        new DeliveryRequest.TemplateRef("sl_ticket_copy_v1", "es", List.of("Banca", "123", "RD$170.00")),
                        DeliveryRequest.Fallback.NONE));
    }

    private static Principal posPrincipal() {
        return new Principal("sl", TENANT, Set.of(SenderTrust.SEND_SCOPE), Map.of(), Instant.now());
    }
}
