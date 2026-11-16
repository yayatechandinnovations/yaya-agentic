package com.yayatechandinnovations.yayaagentic.headless;

import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * {@code V91__headless_runs.sql} on a database that stands at V90 with data in
 * it, as a deployed platform does. (The empty-database case is every other
 * test: each run of the suite migrates a fresh database from V1.)
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class HeadlessRunsMigrationTest {

    @Autowired PostgreSQLContainer<?> postgres;
    @Autowired JdbcTemplate jdbc;

    @Test
    void v91_applies_on_a_v90_database_and_is_then_up_to_date() {
        String database = "hl_v90_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.execute("CREATE DATABASE " + database);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
        JdbcTemplate at = new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(),
                postgres.getPassword()));
        try {
            // The platform as deployed today: V90, a tenant, a month of usage.
            MigrateResult to90 = flyway(url, "90").migrate();
            assertThat(to90.targetSchemaVersion).isEqualTo("90");
            assertThat(tableExists(at, "headless_runs")).isFalse();
            at.update("INSERT INTO tenants (id, display_name, code, owner_user_id) VALUES ('bancadh', 'Banca DH', 'bancadh', '00000000-0000-0000-0000-000000000001')");
            at.update("INSERT INTO tenant_usage_monthly (tenant_id, month, messages) VALUES ('bancadh', '2026-11', 41)");

            // Pinned to 91 so later migrations don't change what this step applies.
            MigrateResult to91 = flyway(url, "91").migrate();

            assertThat(to91.success).isTrue();
            assertThat(to91.migrations).extracting(m -> m.version).containsExactly("91");
            assertThat(tableExists(at, "headless_runs")).isTrue();
            assertThat(at.queryForObject("SELECT messages FROM tenant_usage_monthly WHERE tenant_id = 'bancadh'",
                    Integer.class)).isEqualTo(41);

            // The table as the store uses it: a run, its unique key, its states, the tenant's cascade.
            HeadlessRunStore store = new HeadlessRunStore(at);
            java.time.Instant now = java.time.Instant.now();
            assertThat(store.insertQueued("hr_A", "bancadh", "owner_ops_v1", 11, "summary:e1:1", "u-1",
                    "¿Cuánto se vendió?", "{\"test\":false}", now)).isTrue();
            assertThat(store.insertQueued("hr_B", "bancadh", "owner_ops_v1", 11, "summary:e1:1", "u-1",
                    "¿Cuánto se vendió?", null, now)).isFalse();
            assertThat(store.markRunning("bancadh", "hr_A", now)).isTrue();
            store.setSession("bancadh", "hr_A", UUID.randomUUID().toString());
            assertThat(store.complete("bancadh", "hr_A", "Se vendió 1,250.", "{}", "[]", now)).isTrue();
            assertThat(store.find("bancadh", "hr_A")).get().satisfies(run -> {
                assertThat(run.status()).isEqualTo("completed");
                assertThat(run.answer()).isEqualTo("Se vendió 1,250.");
                assertThat(run.profileVersion()).isEqualTo(11);
            });
            assertThat(at.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'headless_runs'",
                    String.class)).contains("ix_headless_runs_status_created", "headless_runs_idem_uq");
            at.update("DELETE FROM tenants WHERE id = 'bancadh'");
            assertThat(store.find("bancadh", "hr_A")).isEmpty();

            // Later migrations apply on top, and then nothing is left to apply.
            assertThat(flyway(url, null).migrate().success).isTrue();
            assertThat(flyway(url, null).migrate().migrationsExecuted).isZero();
        } finally {
            jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private Flyway flyway(String url, String target) {
        var config = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").baselineOnMigrate(false);
        if (target != null) config.target(target);
        return config.load();
    }

    private static boolean tableExists(JdbcTemplate at, String table) {
        return Boolean.TRUE.equals(at.queryForObject(
                "SELECT to_regclass('public." + table + "') IS NOT NULL", Boolean.class));
    }
}
