package com.yayatechandinnovations.yayaagentic.headless;

import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a headless run costs ({@code docs/guides/admin-panel-sessions.md}
 * §11, §17): one message of the tenant's month whatever its rounds, taken
 * when the run is accepted, and given back when the model was never reached.
 */
class HeadlessRunMeteringTest extends HeadlessRunTestBase {

    @Autowired StringRedisTemplate redis;

    @Test
    void a_run_is_one_message_whatever_its_rounds_and_its_tokens_are_recorded() {
        UsageMeter.Totals before = usage.usage(tenant, null);
        llm.tool("c1", "hl_me", Map.of());
        llm.tool("c2", "hl_sales", Map.of("what", "ayer"));
        llm.tool("c3", "hl_sales", Map.of("what", "hoy"));
        llm.say("Ayer 1,250; hoy 1,250.");

        Map<String, Object> run = runToEnd("¿Cuánto se vendió ayer y hoy?");

        assertThat(run).containsEntry("status", "completed");
        assertThat(llm.requests()).hasSize(4);
        UsageMeter.Totals after = usage.usage(tenant, null);
        assertThat(after.messages()).isEqualTo(before.messages() + 1);
        assertThat(after.inputTokens()).isEqualTo(before.inputTokens() + 400);
        assertThat(after.outputTokens()).isEqualTo(before.outputTokens() + 80);
        assertThat(usage.usageByProfile(tenant, after.month())).filteredOn(p -> p.profileId().equals(PROFILE))
                .singleElement().satisfies(p -> assertThat(p.metered()).isTrue());
    }

    @Test
    void the_allowance_is_checked_when_the_run_is_submitted_and_nothing_is_queued() {
        llm.say("Listo.");
        runToEnd("¿Cuánto se vendió ayer?");
        UsageMeter.Totals used = usage.usage(tenant, null);
        usage.setMonthlyMessages(tenant, (int) used.messages());
        try {
            int rows = rows();
            int modelCalls = llm.requests().size();

            submit(body(runToken("u-" + UUID.randomUUID()), "¿Cuánto se vendió ayer?"))
                    .expectStatus().isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("quota_exceeded")
                    .jsonPath("$.month").isEqualTo(used.month())
                    .jsonPath("$.monthlyMessages").isEqualTo((int) used.messages())
                    .jsonPath("$.messages").isEqualTo((int) used.messages());

            assertThat(rows()).isEqualTo(rows);
            assertThat(llm.requests()).hasSize(modelCalls);
            assertThat(usage.usage(tenant, null).messages()).isEqualTo(used.messages());
        } finally {
            usage.setMonthlyMessages(tenant, null);
        }
    }

    @Test
    void a_run_whose_model_call_failed_with_nothing_produced_is_not_counted() {
        long before = usage.usage(tenant, null).messages();
        llm.failNextRound();

        Map<String, Object> run = runToEnd("¿Cuánto se vendió ayer?");

        assertThat(run).containsEntry("status", "failed");
        assertThat(failureCode(run)).isEqualTo("llm_error");
        assertThat(usage.usage(tenant, null).messages()).isEqualTo(before);
    }

    @Test
    void a_run_that_stopped_after_the_model_was_called_stays_counted() {
        long before = usage.usage(tenant, null).messages();
        llm.tool("c1", "hl_probe", Map.of("how", "confirm"));

        Map<String, Object> run = runToEnd("¿Cómo va la 12?");

        assertThat(failureCode(run)).isEqualTo("needs_interaction");
        assertThat(usage.usage(tenant, null).messages()).isEqualTo(before + 1);
    }

    @Test
    void a_refused_submit_takes_no_message() {
        long before = usage.usage(tenant, null).messages();

        Map<String, Object> noProfile = body(runToken("u-" + UUID.randomUUID()), "¿Cuánto se vendió ayer?");
        noProfile.put("profileId", "no_such_profile");
        submit(noProfile).expectStatus().isNotFound();
        submit(body(staffToken("u-" + UUID.randomUUID(), Map.of()), "¿Cuánto se vendió ayer?"))
                .expectStatus().isForbidden();

        assertThat(usage.usage(tenant, null).messages()).isEqualTo(before);
    }

    @Test
    void the_per_user_message_limit_is_neither_consulted_nor_spent() {
        String subject = "u-" + UUID.randomUUID();
        llm.say("Listo.");

        Map<String, Object> run = finished(accepted(body(runToken(subject), "¿Cuánto se vendió ayer?")));

        assertThat(run).containsEntry("status", "completed");
        // The sessions API counts a user's messages under sess-msg; a run has its own tenant-wide counter.
        assertThat(redis.keys("yaya:chat:rl:sess-msg:" + tenant + ":*")).isEmpty();
        assertThat(redis.keys("yaya:chat:rl:sess-start:" + tenant + ":*")).isEmpty();
        assertThat(redis.keys("yaya:chat:rl:headless:" + tenant + ":*")).hasSize(1);
    }

    private int rows() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM headless_runs WHERE tenant_id = ?", Integer.class, tenant);
        return n == null ? 0 : n;
    }
}
