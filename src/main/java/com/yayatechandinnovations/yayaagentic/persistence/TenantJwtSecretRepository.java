package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantJwtSecretRepository extends JpaRepository<TenantJwtSecretEntity, Long> {

    /** Tenant scan, called on cache miss in SharedSecretJwtAuthenticator. */
    List<TenantJwtSecretEntity> findByTenantId(String tenantId);

    Optional<TenantJwtSecretEntity> findByTenantIdAndIss(String tenantId, String iss);

    /** Issuer → tenant resolution for tenant-less service endpoints
     *  ({@code StructuredCompletionAuthenticator}): every tenant that trusts {@code iss}. */
    @com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped(
            "token-bearer lookup: the tenant is what the token's issuer + signature resolve to")
    List<TenantJwtSecretEntity> findByIss(String iss);
}
