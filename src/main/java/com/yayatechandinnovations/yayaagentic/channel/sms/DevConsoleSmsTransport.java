package com.yayatechandinnovations.yayaagentic.channel.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

/**
 * Logs the rendered SMS to the console. Wired as the unconditional fallback
 * via {@link DevConsoleSmsTransport.Auto} so a deployment with no real SMS
 * provider still works in dev — OTP codes land in the log where an operator
 * can paste them.
 *
 * <p>In production a typed {@link SmsTransport} bean takes over and this
 * fallback never runs.
 */
public class DevConsoleSmsTransport implements SmsTransport {

    private static final Logger log = LoggerFactory.getLogger(DevConsoleSmsTransport.class);

    @Override
    public String name() { return "dev-console"; }

    @Override
    public void send(SmsMessage msg) {
        log.info("[SMS] from={} to={}\n----- body -----\n{}\n----- end -----",
                msg.fromPhone(), msg.toPhone(), msg.bodyText());
    }

    @Configuration
    public static class Auto {
        @Bean
        @ConditionalOnMissingBean(SmsTransport.class)
        public SmsTransport devConsoleSmsTransport() { return new DevConsoleSmsTransport(); }
    }
}
