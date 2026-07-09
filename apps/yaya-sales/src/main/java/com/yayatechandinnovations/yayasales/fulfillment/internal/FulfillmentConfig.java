package com.yayatechandinnovations.yayasales.fulfillment.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SalesDeliveryProperties.class)
class FulfillmentConfig {
}
