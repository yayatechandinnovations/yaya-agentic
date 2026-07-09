package com.yayatechandinnovations.yayasales.integration.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The system-message bridge knobs (design §11.3, §13). The sender credential is
 * whatever bearer the configured yaya-agentic sender trust verifies (a
 * shared-secret JWT or API key carrying {@code system:inbound:send} —
 * {@code system-messaging-design.md} §4); yaya-sales treats it as opaque.
 */
@ConfigurationProperties(prefix = "yaya.sales.notifications")
public record AgentNotificationProperties(
        Boolean enabled,
        String agentBaseUrl,
        String tenantId,
        String senderId,
        String senderToken) {
}
