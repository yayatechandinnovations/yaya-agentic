package com.yayatechandinnovations.yayaagentic.knowledge.blob;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the ClamAV daemon, bound from
 * {@code yaya.agentic.knowledge.clamav}. Only consulted when
 * {@code malware-scan-enabled=true}.
 */
@ConfigurationProperties(prefix = "yaya.agentic.knowledge.clamav")
public record ClamAvProperties(String host, Integer port, Integer timeoutMs) {
    public ClamAvProperties {
        if (host == null || host.isBlank()) host = "localhost";
        if (port == null || port < 1) port = 3310;
        if (timeoutMs == null || timeoutMs < 1) timeoutMs = 10_000;
    }
}
