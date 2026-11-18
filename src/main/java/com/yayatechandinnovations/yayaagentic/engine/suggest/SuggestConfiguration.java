package com.yayatechandinnovations.yayaagentic.engine.suggest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link SuggestProperties} ({@code yaya.agentic.suggest.*}). */
@Configuration
@EnableConfigurationProperties(SuggestProperties.class)
class SuggestConfiguration {
}
