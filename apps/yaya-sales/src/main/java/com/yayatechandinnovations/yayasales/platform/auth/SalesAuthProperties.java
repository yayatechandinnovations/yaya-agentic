package com.yayatechandinnovations.yayasales.platform.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Auth configuration (design §11.3, §15). {@code issuerUri} is the OIDC issuer
 * whose JWTs authenticate operators and forwarded shopper principals — the same
 * issuer trust yaya-agentic uses. {@code serviceSecrets} maps a yaya-agentic
 * tenant id to the base64 HMAC secret verifying its
 * {@code X-Yaya-Service-Token} (minted by the agent's HttpToolDispatcher).
 */
@ConfigurationProperties(prefix = "yaya.sales.auth")
public record SalesAuthProperties(String issuerUri, Map<String, String> serviceSecrets,
                                  java.util.List<String> corsAllowedOrigins,
                                  String operatorTenantClaim,
                                  Boolean enforceOperatorTenant) {

    public SalesAuthProperties {
        serviceSecrets = serviceSecrets == null ? Map.of() : Map.copyOf(serviceSecrets);
        corsAllowedOrigins = corsAllowedOrigins == null
                ? java.util.List.of() : java.util.List.copyOf(corsAllowedOrigins);
        if (operatorTenantClaim == null || operatorTenantClaim.isBlank()) {
            operatorTenantClaim = "org";
        }
        if (enforceOperatorTenant == null) {
            enforceOperatorTenant = true;   // secure by default; opt OUT explicitly
        }
    }

    public boolean jwtConfigured() {
        return issuerUri != null && !issuerUri.isBlank();
    }
}
