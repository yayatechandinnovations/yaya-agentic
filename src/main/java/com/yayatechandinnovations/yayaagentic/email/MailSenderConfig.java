package com.yayatechandinnovations.yayaagentic.email;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the {@link MailSender} implementation based on
 * {@code yaya.agentic.email.provider}:
 * <ul>
 *   <li>{@code sendgrid} → {@link SendGridMailSender} hits SendGrid's v3
 *       HTTPS API. Refuses to boot if {@code SENDGRID_API_KEY} is blank.</li>
 *   <li>anything else (including blank/missing) → {@link
 *       DevConsoleMailSender}, the unconditional fallback that logs every
 *       outbound message to the application log.</li>
 * </ul>
 *
 * <p>Order matters: the SendGrid @Bean is gated on {@link
 * ConditionalOnProperty}; the dev-console @Bean is gated on
 * {@link ConditionalOnMissingBean} so it yields whenever a real sender is
 * present.</p>
 */
@Configuration
public class MailSenderConfig {

    @Bean
    @ConditionalOnProperty(prefix = "yaya.agentic.email", name = "provider", havingValue = "sendgrid")
    public MailSender sendGridMailSender(YayaAgenticProperties props) {
        return new SendGridMailSender(props);
    }

    @Bean
    @ConditionalOnMissingBean(MailSender.class)
    public MailSender devConsoleMailSender(YayaAgenticProperties props) {
        return new DevConsoleMailSender(props);
    }
}
