package com.yayatechandinnovations.yayaagentic.email;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link DevConsoleMailSender} as the unconditional fallback
 * {@link MailSender} bean. Switched to a @Configuration + @Bean factory
 * (away from @Component + @ConditionalOnMissingBean) because the
 * component-scan + conditional-evaluation ordering for "missing bean of
 * the type I myself implement" is brittle — @Bean methods are the
 * canonical Spring Boot pattern for this.
 *
 * <p>When the SendGrid adapter ships in M5b it will register its own
 * {@link MailSender @Bean} (gated on its own conditional); this fallback
 * then becomes inert via the {@link ConditionalOnMissingBean} guard.</p>
 */
@Configuration
public class MailSenderConfig {

    @Bean
    @ConditionalOnMissingBean(MailSender.class)
    public MailSender devConsoleMailSender(YayaAgenticProperties props) {
        return new DevConsoleMailSender(props);
    }
}
