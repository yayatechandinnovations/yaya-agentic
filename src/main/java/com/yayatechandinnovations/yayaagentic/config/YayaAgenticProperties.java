package com.yayatechandinnovations.yayaagentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "yaya.agentic")
public record YayaAgenticProperties(
        String defaultPersonality,
        String defaultTenant,
        Session session,
        Llm llm,
        Cors cors,
        Auth auth,
        HttpTools httpTools
) {
    public record Session(Duration idleTimeout) {}

    public record Llm(String provider) {}

    public record Cors(List<String> allowedOrigins) {}

    public record HttpTools(
            List<String> egressAllowlist,
            boolean allowPrivateNetworks,
            Duration defaultTimeout
    ) {}

    public record Auth(
            boolean allowAnonymous,
            Oidc oidc,
            ServiceToken serviceToken,
            DelegatedHost delegatedHost
    ) {
        public record Oidc(List<Issuer> issuers, Duration jwksCacheTtl, Duration clockSkew) {
            public record Issuer(String iss, String jwksUri, List<String> audiences) {}
        }

        public record ServiceToken(Map<String, String> tenantSecrets) {}

        public record DelegatedHost(String publicKeyPem) {}
    }
}
