package com.yayatechandinnovations.yayasales.payments.internal.providers;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the PSP property records — the app has no global
 * {@code @ConfigurationPropertiesScan}, so each module enables its own.
 */
@Configuration
@EnableConfigurationProperties({DLocalProperties.class, StripeProperties.class, PayPalProperties.class})
class PaymentsProvidersConfig {
}
