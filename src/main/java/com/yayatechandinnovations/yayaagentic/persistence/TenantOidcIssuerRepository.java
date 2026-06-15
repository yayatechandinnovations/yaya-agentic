package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantOidcIssuerRepository extends JpaRepository<TenantOidcIssuerEntity, Long> {

    /** Loads every trusted issuer for a tenant. Called on first auth attempt
     *  per tenant; result is cached in OidcAuthenticator until the next
     *  {@code TenantOidcIssuersChangedEvent}. */
    List<TenantOidcIssuerEntity> findByTenantId(String tenantId);

    Optional<TenantOidcIssuerEntity> findByTenantIdAndIss(String tenantId, String iss);
}
