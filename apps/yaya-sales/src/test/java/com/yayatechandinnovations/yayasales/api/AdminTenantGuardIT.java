package com.yayatechandinnovations.yayasales.api;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import com.yayatechandinnovations.yayasales.stores.StoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §15 admin-plane tenant binding: an operator token acts only on stores of the
 * tenant its claim names. Cross-tenant access answers exactly like a missing
 * store; store creation and tenant-wide listing are bound to the claim too;
 * service tokens never reach the admin plane.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminTenantGuardIT {

    static final String TENANT_A = "tenant-guard-a";
    static final String TENANT_B = "tenant-guard-b";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("yaya_sales");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("yaya.sales.secrets.encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("yaya.sales.outbox.poll-enabled", () -> "false");
        r.add("yaya.sales.inventory.sweep-enabled", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired StoreService storeService;

    StoreId storeA;
    StoreId storeB;

    @BeforeEach
    void twoTenants() {
        storeA = storeService.create(new TenantRef(TENANT_A), "A Store", "DOP",
                StorePolicies.defaults()).id();
        storeB = storeService.create(new TenantRef(TENANT_B), "B Store", "DOP",
                StorePolicies.defaults()).id();
    }

    private static MockHttpServletRequestBuilder operatorOf(String tenant,
                                                            MockHttpServletRequestBuilder req) {
        return req.with(jwt().jwt(j -> j.subject("op-1")
                .claim("roles", List.of("owner"))
                .claim("org", tenant)));
    }

    @Test
    void operatorReachesOwnStoreButNotAnotherTenants() throws Exception {
        mvc.perform(operatorOf(TENANT_A, get("/v1/sales/admin/orders"))
                        .header("X-Yaya-Store-Id", storeA.value()))
                .andExpect(status().isOk());

        // Foreign store and missing store answer identically — no existence leak.
        mvc.perform(operatorOf(TENANT_A, get("/v1/sales/admin/orders"))
                        .header("X-Yaya-Store-Id", storeB.value()))
                .andExpect(status().isForbidden());
        mvc.perform(operatorOf(TENANT_A, get("/v1/sales/admin/orders"))
                        .header("X-Yaya-Store-Id", "no-such-store"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listValuedClaimGrantsEveryNamedTenant() throws Exception {
        MockHttpServletRequestBuilder req = get("/v1/sales/admin/orders")
                .header("X-Yaya-Store-Id", storeB.value())
                .with(jwt().jwt(j -> j.subject("op-multi")
                        .claim("roles", List.of("owner"))
                        .claim("org", List.of(TENANT_A, TENANT_B))));
        mvc.perform(req).andExpect(status().isOk());
    }

    @Test
    void storeCreationAndListingAreBoundToTheClaim() throws Exception {
        mvc.perform(operatorOf(TENANT_A, post("/v1/sales/admin/stores"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT_B
                                + "\",\"displayName\":\"Sneaky\",\"defaultCurrency\":\"DOP\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(operatorOf(TENANT_A, get("/v1/sales/admin/stores"))
                        .param("tenantId", TENANT_B))
                .andExpect(status().isForbidden());

        mvc.perform(operatorOf(TENANT_A, post("/v1/sales/admin/stores"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT_A
                                + "\",\"displayName\":\"Legit\",\"defaultCurrency\":\"DOP\"}"))
                .andExpect(status().isCreated());
        mvc.perform(operatorOf(TENANT_A, get("/v1/sales/admin/stores"))
                        .param("tenantId", TENANT_A))
                .andExpect(status().isOk());
    }

    @Test
    void tokenWithoutTheTenantClaimIsRefusedWithGuidance() throws Exception {
        mvc.perform(get("/v1/sales/admin/orders")
                        .header("X-Yaya-Store-Id", storeA.value())
                        .with(jwt().jwt(j -> j.subject("op-1")
                                .claim("roles", List.of("owner")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceTokensNeverReachTheAdminPlane() throws Exception {
        // Any non-operator authentication is a different caller class (§10.1).
        mvc.perform(get("/v1/sales/admin/orders")
                        .header("X-Yaya-Store-Id", storeA.value())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user("svc")
                                .authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("ROLE_SERVICE"))))
                .andExpect(status().isForbidden());
    }
}
