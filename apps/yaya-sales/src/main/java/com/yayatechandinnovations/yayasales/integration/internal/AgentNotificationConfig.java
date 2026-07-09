package com.yayatechandinnovations.yayasales.integration.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Disabled (the default) registers NO publisher bean — with zero publishers the
 * relay leaves rows PENDING rather than marking them published-to-nobody
 * ({@link com.yayatechandinnovations.yayasales.platform.outbox.OutboxRelay}).
 */
@Configuration
@EnableConfigurationProperties(AgentNotificationProperties.class)
public class AgentNotificationConfig {

    @Bean
    @ConditionalOnProperty(name = "yaya.sales.notifications.enabled", havingValue = "true")
    AgentNotificationPublisher agentNotificationPublisher(AgentNotificationProperties properties,
                                                          ObjectProvider<OrderService> orders,
                                                          ObjectMapper json,
                                                          RestClient.Builder clientBuilder) {
        // Enabled but unaddressed would retry every delivery event forever —
        // refuse to boot instead.
        require(properties.agentBaseUrl(), "agent-base-url");
        require(properties.tenantId(), "tenant-id");
        require(properties.senderId(), "sender-id");
        require(properties.senderToken(), "sender-token");
        return new AgentNotificationPublisher(properties, orders, json, clientBuilder);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "yaya.sales.notifications.enabled=true requires yaya.sales.notifications." + name);
        }
    }
}
