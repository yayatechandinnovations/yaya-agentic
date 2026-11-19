package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingId;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnOverrideDenied;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnUnresolvedPolicy;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAutoReplyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ticket-share design §8 P4: the fixed line goes out once per (sender, phone) per 24 h, only under AUTO_REPLY. */
class UnresolvedAutoReplyServiceTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");
    private static final SenderId SENDER = new SenderId("wa_1");
    private static final String PHONE = "+18095551234";
    private static final String TEXT = "Gracias por escribir a Banca La Suerte. Este número envía copias de tickets.";
    private static final Instant NOW = Instant.parse("2026-11-18T15:00:00Z");

    private ChannelAutoReplyRepository repo;
    private OutboundDispatcher outbound;
    private UnresolvedAutoReplyService service;

    @BeforeEach
    void setUp() {
        repo = mock(ChannelAutoReplyRepository.class);
        outbound = mock(OutboundDispatcher.class);
        service = new UnresolvedAutoReplyService(repo, outbound, Clock.fixed(NOW, ZoneOffset.UTC),
                TransactionOperations.withoutTransaction());
    }

    private static ChannelBinding binding(OnUnresolvedPolicy policy, String text) {
        return new ChannelBinding(new BindingId(1L), TENANT, new ChannelEndpointRef.WhatsAppSenderRef(SENDER),
                new Ids.ProfileId("owner_ops_v1", 1), Optional.empty(), new RoutingRule.MatchAny(),
                SessionPolicy.defaults(), 100, BindingStatus.ACTIVE, NOW, null, 7L, policy, Set.of(),
                OnOverrideDenied.IGNORE, text);
    }

    private void claimAnswers(int rows) {
        when(repo.claim(eq("wa_1"), eq(PHONE), any(OffsetDateTime.class), any(OffsetDateTime.class))).thenReturn(rows);
    }

    @Test
    void first_unknown_caller_hears_the_line_and_the_claim_is_recorded() {
        claimAnswers(1);

        assertThat(service.replyIfDue(TENANT, SENDER, PHONE, binding(OnUnresolvedPolicy.AUTO_REPLY, TEXT))).isTrue();

        verify(outbound).dispatchGreeting(eq(TENANT), eq(SENDER), eq(PHONE), any(), eq(TEXT), anyList());
        ArgumentCaptor<OffsetDateTime> now = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo).claim(eq("wa_1"), eq(PHONE), now.capture(), cutoff.capture());
        assertThat(now.getValue().toInstant()).isEqualTo(NOW);
        assertThat(cutoff.getValue().toInstant()).isEqualTo(NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void a_standing_claim_means_no_reply_and_no_exception() {
        // The database decides the 24 h boundary in one statement: a racing or
        // repeated claim reads 0 rows, never a duplicate-key error that would
        // leave the REQUIRES_NEW transaction rollback-only.
        claimAnswers(0);

        assertThat(service.claim(SENDER, PHONE)).isFalse();
        assertThat(service.replyIfDue(TENANT, SENDER, PHONE, binding(OnUnresolvedPolicy.AUTO_REPLY, TEXT))).isFalse();

        verify(outbound, never()).dispatchGreeting(any(), any(), anyString(), any(), anyString(), anyList());
    }

    @Test
    void reject_and_anonymous_bindings_never_reply() {
        assertThat(service.replyIfDue(TENANT, SENDER, PHONE, binding(OnUnresolvedPolicy.REJECT, TEXT))).isFalse();
        assertThat(service.replyIfDue(TENANT, SENDER, PHONE, binding(OnUnresolvedPolicy.ANONYMOUS, TEXT))).isFalse();
        assertThat(service.replyIfDue(TENANT, SENDER, PHONE, binding(OnUnresolvedPolicy.AUTO_REPLY, "  "))).isFalse();
        assertThat(service.replyIfDue(TENANT, SENDER, PHONE, null)).isFalse();
        verify(outbound, never()).dispatchGreeting(any(), any(), anyString(), any(), anyString(), anyList());
        verify(repo, never()).claim(anyString(), anyString(), any(), any());
    }

    @Test
    void a_failing_send_still_counts_as_said_so_the_caller_is_not_spammed_by_retries() {
        claimAnswers(1);
        doThrow(new IllegalStateException("twilio down")).when(outbound)
                .dispatchGreeting(any(), any(), anyString(), any(), anyString(), anyList());

        assertThat(service.replyIfDue(TENANT, SENDER, PHONE, binding(OnUnresolvedPolicy.AUTO_REPLY, TEXT))).isTrue();

        verify(repo).claim(eq("wa_1"), eq(PHONE), any(), any());
    }

    @Test
    void binding_exposes_the_text_only_under_auto_reply() {
        assertThat(binding(OnUnresolvedPolicy.AUTO_REPLY, TEXT).autoReplyText()).contains(TEXT);
        assertThat(binding(OnUnresolvedPolicy.REJECT, TEXT).autoReplyText()).isEmpty();
        assertThat(binding(OnUnresolvedPolicy.AUTO_REPLY, null).autoReplyText()).isEmpty();
        assertThat(List.of(OnUnresolvedPolicy.values())).contains(OnUnresolvedPolicy.AUTO_REPLY);
        assertThat(OnUnresolvedPolicy.parse("auto_reply")).isEqualTo(OnUnresolvedPolicy.AUTO_REPLY);
    }
}
