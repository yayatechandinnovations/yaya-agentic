package com.yayatechandinnovations.yayaagentic.usage;

import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class UsageMeterTest {

    @Autowired UsageMeter meter;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantRepository tenants;

    private String newTenant(String settingsJson) {
        String id = "usage-" + UUID.randomUUID().toString().substring(0, 8);
        TenantEntity tenant = new TenantEntity(id, "Usage " + id);
        if (settingsJson != null) tenant.setSettingsJson(settingsJson);
        tenants.save(tenant);
        return id;
    }

    @Test
    void concurrent_turns_never_admit_more_than_the_allowance() throws Exception {
        String tenant = newTenant(null);
        meter.setMonthlyMessages(tenant, 7);
        int callers = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        for (int i = 0; i < callers; i++) {
            pool.submit(() -> {
                try {
                    go.await();
                    meter.admit(tenant, "owner", true);
                    admitted.incrementAndGet();
                } catch (QuotaExceededException e) {
                    refused.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(admitted.get()).isEqualTo(7);
        assertThat(refused.get()).isEqualTo(callers - 7);
        assertThat(meter.usage(tenant, null).messages()).isEqualTo(7);
    }

    @Test
    void a_turn_counts_only_if_the_model_was_called() {
        String tenant = newTenant(null);
        meter.setMonthlyMessages(tenant, 1);

        // The model was never called: the message goes back, the next turn is admitted.
        meter.settle(meter.admit(tenant, "owner", true), false, 0, 0);
        assertThat(meter.usage(tenant, null).messages()).isZero();

        UsageMeter.Reservation counted = meter.admit(tenant, "owner", true);
        meter.settle(counted, true, 900, 120);
        assertThat(meter.usage(tenant, null))
                .isEqualTo(new UsageMeter.Totals(counted.month(), 1, 900, 120, 0, 0, 1));

        assertThatThrownBy(() -> meter.admit(tenant, "owner", true))
                .isInstanceOfSatisfying(QuotaExceededException.class, e -> {
                    assertThat(e.messages()).isEqualTo(1);
                    assertThat(e.monthlyMessages()).isEqualTo(1);
                    assertThat(e.month()).isEqualTo(counted.month());
                });
        // A refusal changes nothing.
        assertThat(meter.usage(tenant, null).messages()).isEqualTo(1);
    }

    @Test
    void tokens_are_recorded_without_a_message_and_the_allowance_can_be_read_without_taking_from_it() {
        String tenant = newTenant(null);
        assertThat(meter.hasAllowance(tenant)).isTrue();
        meter.setMonthlyMessages(tenant, 1);
        assertThat(meter.hasAllowance(tenant)).isTrue();
        assertThat(meter.usage(tenant, null).messages()).isZero();

        // An opening the model wrote: tokens, no message.
        meter.recordTokens(tenant, "owner", true, 300, 40);
        meter.recordTokens(tenant, "customer-sales", false, 50, 5);
        // A turn that is given back still leaves the tokens it was billed.
        UsageMeter.Reservation returned = meter.admit(tenant, "owner", true);
        assertThat(meter.hasAllowance(tenant)).isFalse();
        meter.settle(returned, false, 10, 2);

        assertThat(meter.hasAllowance(tenant)).isTrue();
        assertThat(meter.usage(tenant, null))
                .isEqualTo(new UsageMeter.Totals(returned.month(), 0, 310, 42, 0, 0, 1));
        assertThat(meter.usageByProfile(tenant, returned.month())).containsExactly(
                new UsageMeter.ProfileTotals("customer-sales", false, 0, 50, 5, 0, 0),
                new UsageMeter.ProfileTotals("owner", true, 0, 310, 42, 0, 0));

        meter.settle(meter.admit(tenant, "owner", true), true, 0, 0);
        assertThat(meter.hasAllowance(tenant)).isFalse();
        meter.setMonthlyMessages(tenant, 0);
        assertThat(meter.hasAllowance(tenant)).isFalse();
    }

    @Test
    void cache_reads_and_writes_are_kept_next_to_the_input_tokens_they_are_part_of() {
        String tenant = newTenant(null);

        // A cold turn writes the prefix to the cache; the next one reads it.
        UsageMeter.Reservation cold = meter.admit(tenant, "owner", true);
        meter.settle(cold, true, new UsageMeter.Tokens(30_000, 200, 0, 28_000));
        meter.settle(meter.admit(tenant, "owner", true), true, new UsageMeter.Tokens(30_500, 150, 28_000, 400));
        // An opening the model wrote, on an unmetered profile.
        meter.recordTokens(tenant, "customer-sales", false, new UsageMeter.Tokens(900, 60, 800, 0));

        assertThat(meter.usage(tenant, null))
                .isEqualTo(new UsageMeter.Totals(cold.month(), 2, 60_500, 350, 28_000, 28_400, null));
        assertThat(meter.usageByProfile(tenant, cold.month())).containsExactly(
                new UsageMeter.ProfileTotals("customer-sales", false, 0, 900, 60, 800, 0),
                new UsageMeter.ProfileTotals("owner", true, 2, 60_500, 350, 28_000, 28_400));
    }

    @Test
    void unmetered_profiles_are_reported_but_never_draw_on_or_hit_the_allowance() {
        String tenant = newTenant(null);
        meter.setMonthlyMessages(tenant, 0);

        UsageMeter.Reservation sales = meter.admit(tenant, "customer-sales", false);
        meter.settle(sales, true, 50, 10);
        meter.settle(meter.admit(tenant, "customer-sales", false), true, 70, 20);

        assertThat(meter.usage(tenant, null).messages()).isZero();
        assertThat(meter.usageByProfile(tenant, sales.month())).containsExactly(
                new UsageMeter.ProfileTotals("customer-sales", false, 2, 120, 30, 0, 0));
        assertThatThrownBy(() -> meter.admit(tenant, "owner", true))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void no_allowance_set_means_unlimited_and_still_counted() {
        String tenant = newTenant(null);
        for (int i = 0; i < 3; i++) meter.settle(meter.admit(tenant, "owner", true), true, 10, 1);
        UsageMeter.Totals totals = meter.usage(tenant, null);
        assertThat(totals.messages()).isEqualTo(3);
        assertThat(totals.monthlyMessages()).isNull();
    }

    @Test
    void the_month_turns_over_in_the_tenants_time_zone_or_utc_without_one() {
        // 02:00 UTC on Dec 1st is still Nov 30th, 22:00 in Santo Domingo.
        Clock clock = Clock.fixed(Instant.parse("2026-12-01T02:00:00Z"), ZoneOffset.UTC);
        UsageMeter atTurnover = new UsageMeter(jdbc, clock);

        String local = newTenant("{\"timeZone\":\"America/Santo_Domingo\"}");
        String utc = newTenant(null);
        String broken = newTenant("{\"timeZone\":\"Not/AZone\"}");

        assertThat(atTurnover.admit(local, "owner", true).month()).isEqualTo("2026-11");
        assertThat(atTurnover.admit(utc, "owner", true).month()).isEqualTo("2026-12");
        assertThat(atTurnover.admit(broken, "owner", true).month()).isEqualTo("2026-12");
        assertThat(atTurnover.usage(local, null).month()).isEqualTo("2026-11");
        assertThat(atTurnover.usage(local, "2026-11").messages()).isEqualTo(1);
        assertThat(atTurnover.usage(local, "2026-12").messages()).isZero();
    }

    @Test
    void a_refusal_is_spoken_as_one_fixed_sentence_on_text_channels() {
        Flux<TurnEvent> refused = Flux.error(new QuotaExceededException("t", "2026-11", 5, 5).inLanguage("es"));

        List<TurnEvent> turn = QuotaRefusals.spoken(refused).collectList().block();

        assertThat(turn).hasSize(2);
        assertThat(((TurnEvent.Token) turn.get(0)).text()).isEqualTo(QuotaRefusals.text("es")).contains("este mes");
        assertThat(turn.get(1)).isInstanceOf(TurnEvent.End.class);
        assertThat(QuotaRefusals.text(null)).contains("This month").doesNotContain("este mes");
        // Any other failure is not swallowed.
        assertThatThrownBy(() -> QuotaRefusals.spoken(Flux.<TurnEvent>error(new IllegalStateException("x")))
                .collectList().block()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void media_is_counted_for_every_profile_and_voice_has_its_own_allowance() {
        String tenant = newTenant(null);
        meter.recordVoiceSeconds(tenant, "panel_owner", 14);
        meter.recordVoiceSeconds(tenant, "customer_chat", 6);
        meter.recordImagesIn(tenant, "panel_owner", 2);

        UsageMeter.Totals totals = meter.usage(tenant, null);
        assertThat(totals.voiceSeconds()).isEqualTo(20);
        assertThat(totals.imagesIn()).isEqualTo(2);
        assertThat(meter.voiceSecondsUsed(tenant)).isEqualTo(20);
        assertThat(meter.usageByProfile(tenant, totals.month()))
                .extracting(UsageMeter.ProfileTotals::profileId, UsageMeter.ProfileTotals::voiceSeconds,
                        UsageMeter.ProfileTotals::imagesIn)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("customer_chat", 6L, 0L),
                        org.assertj.core.groups.Tuple.tuple("panel_owner", 14L, 2L));
        // messages untouched
        assertThat(totals.messages()).isZero();

        UsageMeter capped = new UsageMeter(jdbc, Clock.systemUTC(), "20");
        assertThat(capped.voiceMonthlySeconds()).isEqualTo(20);
        assertThat(capped.hasVoiceAllowance(tenant)).isFalse();
        assertThat(new UsageMeter(jdbc, Clock.systemUTC(), "21").hasVoiceAllowance(tenant)).isTrue();
        UsageMeter unlimited = new UsageMeter(jdbc, Clock.systemUTC(), "");
        assertThat(unlimited.voiceMonthlySeconds()).isNull();
        assertThat(unlimited.hasVoiceAllowance(tenant)).isTrue();
        assertThat(UsageMeter.parseAllowance("null")).isNull();
        assertThat(UsageMeter.parseAllowance("-1")).isNull();
    }
}
