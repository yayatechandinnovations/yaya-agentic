package com.yayatechandinnovations.yayaagentic.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the dispatch-time path → absolute URL join (§6.1).
 */
class BaseUrlResolverTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void joins_tenant_host_base_with_path() {
        BaseUrlResolver resolver = resolver(tenant("https://api.acme.example", "[]"));

        URI uri = resolver.resolve(TENANT, "/v1/orders/42", null);

        assertThat(uri.toString()).isEqualTo("https://api.acme.example/v1/orders/42");
    }

    @Test
    void preserves_path_prefix_on_host_base_url() {
        BaseUrlResolver resolver = resolver(tenant("https://api.acme.example/api/v2", "[]"));

        URI uri = resolver.resolve(TENANT, "/orders/42", null);

        assertThat(uri.toString()).isEqualTo("https://api.acme.example/api/v2/orders/42");
    }

    @Test
    void preserves_path_prefix_when_host_base_has_trailing_slash() {
        BaseUrlResolver resolver = resolver(tenant("https://api.acme.example/api/v2/", "[]"));

        URI uri = resolver.resolve(TENANT, "/orders/42", null);

        assertThat(uri.toString()).isEqualTo("https://api.acme.example/api/v2/orders/42");
    }

    @Test
    void rejects_when_tenant_has_no_host_base_url() {
        BaseUrlResolver resolver = resolver(tenant(null, "[]"));

        assertThatThrownBy(() -> resolver.resolve(TENANT, "/anything", null))
                .isInstanceOf(BaseUrlResolutionException.class)
                .hasMessageContaining("no host_base_url configured");
    }

    @Test
    void rejects_blank_rendered_path() {
        BaseUrlResolver resolver = resolver(tenant("https://api.acme.example", "[]"));

        assertThatThrownBy(() -> resolver.resolve(TENANT, "", null))
                .isInstanceOf(BaseUrlResolutionException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejects_path_without_leading_slash() {
        BaseUrlResolver resolver = resolver(tenant("https://api.acme.example", "[]"));

        assertThatThrownBy(() -> resolver.resolve(TENANT, "v1/orders", null))
                .isInstanceOf(BaseUrlResolutionException.class)
                .hasMessageContaining("'/'");
    }

    @Test
    void rejects_protocol_relative_path() {
        BaseUrlResolver resolver = resolver(tenant("https://api.acme.example", "[]"));

        assertThatThrownBy(() -> resolver.resolve(TENANT, "//evil.com/path", null))
                .isInstanceOf(BaseUrlResolutionException.class)
                .hasMessageContaining("protocol-relative");
    }

    @Test
    void rejects_unknown_tenant() {
        TenantRepository repo = mock(TenantRepository.class);
        when(repo.findById(TENANT.value())).thenReturn(Optional.empty());

        BaseUrlResolver resolver = new BaseUrlResolver(repo, json);

        assertThatThrownBy(() -> resolver.resolve(TENANT, "/anything", null))
                .isInstanceOf(BaseUrlResolutionException.class)
                .hasMessageContaining("unknown tenant");
    }

    @Test
    void accepts_override_matching_tenant_allowlist() {
        BaseUrlResolver resolver = resolver(tenant("https://prod.acme.example",
                "[\"https://staging-*.acme.example\"]"));

        URI uri = resolver.resolve(TENANT, "/v1/ping", "https://staging-1.acme.example");

        assertThat(uri.toString()).isEqualTo("https://staging-1.acme.example/v1/ping");
    }

    @Test
    void rejects_override_not_in_allowlist() {
        BaseUrlResolver resolver = resolver(tenant("https://prod.acme.example",
                "[\"https://staging-*.acme.example\"]"));

        assertThatThrownBy(() -> resolver.resolve(TENANT, "/v1/ping", "https://evil.example"))
                .isInstanceOf(BaseUrlResolutionException.class)
                .hasMessageContaining("not in tenant allowlist");
    }

    @Test
    void uses_tenant_default_when_override_is_blank() {
        BaseUrlResolver resolver = resolver(tenant("https://prod.acme.example", "[]"));

        URI uri = resolver.resolve(TENANT, "/v1/ping", "  ");

        assertThat(uri.toString()).isEqualTo("https://prod.acme.example/v1/ping");
    }

    // ---- helpers ----------------------------------------------------

    private BaseUrlResolver resolver(TenantEntity tenant) {
        TenantRepository repo = mock(TenantRepository.class);
        when(repo.findById(TENANT.value())).thenReturn(Optional.of(tenant));
        return new BaseUrlResolver(repo, json);
    }

    private static TenantEntity tenant(String hostBaseUrl, String allowlistJson) {
        TenantEntity t = new TenantEntity(TENANT.value(), "Acme");
        t.setHostBaseUrl(hostBaseUrl);
        t.setHostBaseUrlAllowlistJson(allowlistJson);
        return t;
    }
}
