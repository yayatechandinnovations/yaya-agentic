package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ConversationHoldEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ConversationHoldRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationHoldServiceTest {

    static final Ids.TenantId TENANT = new Ids.TenantId("yaya");
    static final String PHONE = "+18095551234";

    private final ConversationHoldRepository repo = mock(ConversationHoldRepository.class);
    private final ConversationHoldService svc = new ConversationHoldService(repo);

    @Test
    void no_row_means_the_assistant_answers() {
        when(repo.findByTenantIdAndEndUserPhone(anyString(), anyString())).thenReturn(Optional.empty());
        assertThat(svc.isHeld(TENANT, PHONE)).isFalse();
    }

    @Test
    void a_live_row_holds_the_conversation() {
        when(repo.findByTenantIdAndEndUserPhone(TENANT.value(), PHONE))
                .thenReturn(Optional.of(row(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1))));
        assertThat(svc.isHeld(TENANT, PHONE)).isTrue();
    }

    @Test
    void a_lapsed_row_does_not_mute_the_customer_forever() {
        when(repo.findByTenantIdAndEndUserPhone(TENANT.value(), PHONE))
                .thenReturn(Optional.of(row(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))));
        assertThat(svc.isHeld(TENANT, PHONE)).isFalse();
        assertThat(svc.find(TENANT, PHONE)).isEmpty();
    }

    @Test
    void holding_without_a_duration_lasts_a_day() {
        when(repo.findByTenantIdAndEndUserPhone(anyString(), anyString())).thenReturn(Optional.empty());
        when(repo.save(any(ConversationHoldEntity.class))).thenAnswer(i -> i.getArgument(0));

        ConversationHoldEntity held = svc.hold(TENANT, PHONE, null, "handoff");
        assertThat(held.getHeldUntil()).isCloseTo(OffsetDateTime.now(ZoneOffset.UTC).plusHours(24),
                within(2, java.time.temporal.ChronoUnit.MINUTES));
        assertThat(held.getReason()).isEqualTo("handoff");
    }

    @Test
    void a_longer_hold_than_a_week_is_clamped() {
        when(repo.findByTenantIdAndEndUserPhone(anyString(), anyString())).thenReturn(Optional.empty());
        when(repo.save(any(ConversationHoldEntity.class))).thenAnswer(i -> i.getArgument(0));

        ConversationHoldEntity held = svc.hold(TENANT, PHONE, Duration.ofDays(30), "forgot");
        assertThat(held.getHeldUntil()).isBefore(OffsetDateTime.now(ZoneOffset.UTC).plus(ConversationHoldService.MAX_HOLD).plusMinutes(1));
        assertThat(held.getHeldUntil()).isAfter(OffsetDateTime.now(ZoneOffset.UTC).plus(ConversationHoldService.MAX_HOLD).minusMinutes(1));
    }

    @Test
    void holding_again_extends_the_same_row() {
        ConversationHoldEntity existing = row(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        when(repo.findByTenantIdAndEndUserPhone(TENANT.value(), PHONE)).thenReturn(Optional.of(existing));
        when(repo.save(any(ConversationHoldEntity.class))).thenAnswer(i -> i.getArgument(0));

        ConversationHoldEntity held = svc.hold(TENANT, PHONE, Duration.ofHours(2), "still on it");
        assertThat(held).isSameAs(existing);
        assertThat(held.getHeldUntil()).isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        assertThat(held.getReason()).isEqualTo("still on it");
    }

    @Test
    void releasing_a_conversation_nobody_held_is_a_no_op() {
        when(repo.findByTenantIdAndEndUserPhone(anyString(), anyString())).thenReturn(Optional.empty());
        svc.release(TENANT, PHONE);
        verify(repo).findByTenantIdAndEndUserPhone(TENANT.value(), PHONE);
    }

    @Test
    void releasing_deletes_the_row() {
        ConversationHoldEntity existing = row(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        when(repo.findByTenantIdAndEndUserPhone(TENANT.value(), PHONE)).thenReturn(Optional.of(existing));
        svc.release(TENANT, PHONE);
        verify(repo).delete(existing);
    }

    private static ConversationHoldEntity row(OffsetDateTime until) {
        return new ConversationHoldEntity(TENANT.value(), PHONE, until, "handoff");
    }
}
