package com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * {@code V94__whatsapp_shared_templates.sql} on a database that stands at V93
 * with per-sender template rows (whatsapp-shared-templates-design.md §3.2):
 * two workspaces on the platform account with the same template, the same name
 * with different content, and a workspace on its own account.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class SharedTemplatesMigrationTest {

    @Autowired PostgreSQLContainer<?> postgres;
    @Autowired JdbcTemplate jdbc;

    @Test
    void v94_folds_per_sender_rows_into_one_template_per_account_with_holders() {
        String database = "wt_v93_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.execute("CREATE DATABASE " + database);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
        JdbcTemplate at = new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(),
                postgres.getPassword()));
        try {
            assertThat(flyway(url, "93").migrate().targetSchemaVersion).isEqualTo("93");
            for (String t : List.of("fastcash", "test", "other", "ownbrand")) {
                at.update("INSERT INTO tenants (id, display_name, code, owner_user_id) VALUES (?, ?, ?, "
                        + "'00000000-0000-0000-0000-000000000001')", t, t, t);
            }
            at.update("INSERT INTO whatsapp_messaging_accounts (id, tenant_id, waba_id, friendly_name, status) "
                    + "VALUES ('wma_own', 'ownbrand', 'waba-1', 'Own Brand', 'ACTIVE')");
            sender(at, "snd_fast", "fastcash", "+18455330001", null);
            sender(at, "snd_fast2", "fastcash", "+18455330002", null);
            sender(at, "snd_test", "test", "+18455330003", null);
            sender(at, "snd_other", "other", "+18455330004", null);
            sender(at, "snd_own", "ownbrand", "+18455330005", "wma_own");

            // The same approved template on three platform-account senders of two
            // workspaces; fastcash's is approved, the others lag behind.
            long approved = template(at, "fastcash", "snd_fast", "sl_ops_alert_v1", "Alerta {{1}}.", "h1",
                    "APPROVED", "HX1");
            template(at, "fastcash", "snd_fast2", "sl_ops_alert_v1", "Alerta {{1}}.", "h1", "DRAFT", null);
            template(at, "test", "snd_test", "sl_ops_alert_v1", "Alerta {{1}}.", "h1", "SUBMITTED", "HX1");
            // The same name with other wording: it could never have been approved there.
            template(at, "other", "snd_other", "sl_ops_alert_v1", "Otra cosa {{1}}.", "h2", "DRAFT", null);
            // A template only one workspace has.
            long solo = template(at, "test", "snd_test", "sl_report_v1", "Reporte {{1}}.", "h3", "DRAFT", null);
            // The same name on a workspace's own account is a different template.
            long own = template(at, "ownbrand", "snd_own", "sl_ops_alert_v1", "Alerta {{1}}.", "h1",
                    "REJECTED", null);

            // Pinned to 94 so later migrations don't change what this step applies.
            MigrateResult to94 = flyway(url, "94").migrate();
            assertThat(to94.success).isTrue();
            assertThat(to94.migrations).extracting(m -> m.version).containsExactly("94");

            List<Map<String, Object>> rows = at.queryForList(
                    "SELECT id, account_key, name, status, bsp_content_sid, created_by_tenant "
                            + "FROM whatsapp_templates ORDER BY account_key, name");
            assertThat(rows).extracting(r -> r.get("account_key") + "/" + r.get("name") + "/" + r.get("status"))
                    .containsExactly("platform/sl_ops_alert_v1/APPROVED", "platform/sl_report_v1/DRAFT",
                            "wma_own/sl_ops_alert_v1/REJECTED");
            assertThat(rows.get(0).get("id")).isEqualTo(approved);
            assertThat(rows.get(0).get("bsp_content_sid")).isEqualTo("HX1");
            assertThat(rows.get(0).get("created_by_tenant")).isEqualTo("fastcash");

            assertThat(holders(at, approved)).containsExactly("fastcash", "test");
            assertThat(holders(at, solo)).containsExactly("test");
            assertThat(holders(at, own)).containsExactly("ownbrand");
            assertThat(at.queryForObject("SELECT count(*) FROM whatsapp_template_holders "
                    + "WHERE granted_by = 'migration-v94'", Integer.class)).isEqualTo(4);

            assertThat(at.queryForList("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_name = 'whatsapp_templates'", String.class))
                    .contains("account_key", "created_by_tenant").doesNotContain("tenant_id", "sender_id");
            // One name per account from now on; a workspace's holders go with it.
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> at.update(
                    "INSERT INTO whatsapp_templates (account_key, name, locale, category, body) "
                            + "VALUES ('platform', 'sl_ops_alert_v1', 'es', 'UTILITY', 'x')"))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            at.update("DELETE FROM tenants WHERE id = 'test'");
            assertThat(holders(at, approved)).containsExactly("fastcash");
            assertThat(at.queryForObject("SELECT count(*) FROM whatsapp_templates WHERE id = ?",
                    Integer.class, solo)).isEqualTo(1);

            // Later migrations apply on top, and then nothing is left to apply.
            assertThat(flyway(url, null).migrate().success).isTrue();
            assertThat(flyway(url, null).migrate().migrationsExecuted).isZero();
        } finally {
            jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static void sender(JdbcTemplate at, String id, String tenant, String phone, String account) {
        at.update("INSERT INTO whatsapp_senders (id, tenant_id, phone_e164, display_name, business_profile_json, "
                        + "status, webhook_secret_enc, messaging_account_id) "
                        + "VALUES (?, ?, ?, ?, '{}'::jsonb, 'ACTIVE', decode('00', 'hex'), ?)",
                id, tenant, phone, id, account);
    }

    private static long template(JdbcTemplate at, String tenant, String sender, String name, String body,
                                 String hash, String status, String contentSid) {
        return at.queryForObject("INSERT INTO whatsapp_templates (tenant_id, sender_id, name, locale, category, "
                        + "body, placeholder_count, status, bsp_content_sid, content_hash) "
                        + "VALUES (?, ?, ?, 'es', 'UTILITY', ?, 1, ?, ?, ?) RETURNING id",
                Long.class, tenant, sender, name, body, status, contentSid, hash);
    }

    private static List<String> holders(JdbcTemplate at, long templateId) {
        return at.queryForList("SELECT tenant_id FROM whatsapp_template_holders WHERE template_id = ? "
                + "ORDER BY tenant_id", String.class, templateId);
    }

    private Flyway flyway(String url, String target) {
        var config = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").baselineOnMigrate(false);
        if (target != null) config.target(target);
        return config.load();
    }
}
