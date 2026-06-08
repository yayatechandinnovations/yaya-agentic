package com.yayatechandinnovations.yayaagentic.tenant.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ToolEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ToolRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

/**
 * Direct (non-HTTP) coverage of the path-only migrator (§9.2).
 *
 * <p>The migrator runs against the database directly so its policy decisions
 * (CANDIDATE vs ORIGIN_NOT_TENANT_HOST vs TENANT_HAS_NO_HOST_BASE_URL) are
 * unit-testable without going through the admin REST layer — which would
 * reject the legacy absolute URLs we want to migrate before they ever land.</p>
 */
@SpringBootTest(webEnvironment = NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class AbsoluteToPathMigratorTest {

    @Autowired AbsoluteToPathMigrator migrator;
    @Autowired ToolRepository tools;
    @Autowired TenantRepository tenants;
    @Autowired ObjectMapper json;

    private static final String TENANT = "migrator-test";

    @BeforeEach
    void seed() throws Exception {
        if (!tenants.existsById(TENANT)) {
            TenantEntity t = new TenantEntity(TENANT, "Migrator Test");
            t.setHostBaseUrl("https://api.migrator.example");
            t.setStatus(TenantEntity.Status.ACTIVE);
            tenants.save(t);
        }
        // Pre-seed three HTTP tools with absolute URLs directly via JPA so
        // the path-only validator at the admin layer doesn't block them.
        seedAbsolute("matches-host", "https://api.migrator.example/v1/orders/{id}");
        seedAbsolute("wrong-host",   "https://api.other.example/v1/orders/{id}");
        seedAbsolute("proto-rel",    "//api.migrator.example/v1/orders/{id}");
    }

    private void seedAbsolute(String id, String urlTemplate) throws Exception {
        if (!tools.findByTenantIdAndIdOrderByVersionDesc(TENANT, id).isEmpty()) return;
        Map<String, Object> spec = Map.of(
                "method", "GET",
                "urlTemplate", urlTemplate,
                "authForwarding", "NONE");
        ToolEntity t = new ToolEntity(TENANT, id, 1,
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", "HTTP");
        t.setHandlerHttpSpecJson(json.writeValueAsString(spec));
        tools.save(t);
    }

    @Test
    void plan_classifies_each_tool_correctly() {
        AbsoluteToPathMigrator.Plan plan = migrator.plan(TENANT);

        assertThat(plan.candidates())
                .extracting(AbsoluteToPathMigrator.Candidate::toolId)
                .containsExactly("matches-host");
        assertThat(plan.candidates())
                .extracting(AbsoluteToPathMigrator.Candidate::rewritten)
                .containsExactly("/v1/orders/{id}");

        assertThat(plan.unsafe())
                .extracting(AbsoluteToPathMigrator.Unsafe::toolId)
                .containsExactlyInAnyOrder("wrong-host", "proto-rel");
        assertThat(plan.unsafe())
                .anyMatch(u -> "wrong-host".equals(u.toolId())
                        && "ORIGIN_NOT_TENANT_HOST".equals(u.reason()));
    }

    @Test
    void apply_bumps_version_and_writes_path_only_template() {
        int before = tools.findByTenantIdAndIdOrderByVersionDesc(TENANT, "matches-host")
                .stream().mapToInt(ToolEntity::getVersion).max().orElse(0);
        migrator.apply(TENANT);
        ToolEntity latest = tools.findByTenantIdAndIdOrderByVersionDesc(TENANT, "matches-host").get(0);
        assertThat(latest.getVersion()).isEqualTo(before + 1);
        assertThat(latest.getHandlerHttpSpecJson()).contains("/v1/orders/{id}");
        assertThat(latest.getHandlerHttpSpecJson()).doesNotContain("api.migrator.example");
        // Unsafe tools are never touched.
        ToolEntity wrong = tools.findByTenantIdAndIdOrderByVersionDesc(TENANT, "wrong-host").get(0);
        assertThat(wrong.getHandlerHttpSpecJson()).contains("api.other.example");
    }
}
