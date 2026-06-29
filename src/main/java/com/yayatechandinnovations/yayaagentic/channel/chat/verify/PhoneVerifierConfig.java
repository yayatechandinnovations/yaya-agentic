package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Selects the active {@link PhoneVerifier} ({@code chat-channel-design.md} §20):
 * the real Twilio Verify implementation when a Verify service SID is configured,
 * otherwise the dev no-op. Uses {@code @Bean} + {@code @ConditionalOnMissingBean}
 * (the reliable ordering) rather than class-level conditions on scanned
 * components — same selection pattern as the SMS transport.
 */
@Configuration
public class PhoneVerifierConfig {

    @Bean
    @ConditionalOnProperty(name = "yaya.agentic.chat.verify.twilio-service-sid")
    public PhoneVerifier twilioVerifyPhoneVerifier(
            TwilioProperties props,
            WebClient.Builder webClientBuilder,
            @Value("${yaya.agentic.chat.verify.twilio-service-sid}") String serviceSid,
            @Value("${yaya.agentic.chat.verify.base-url:https://verify.twilio.com}") String verifyBaseUrl) {
        return new TwilioVerifyPhoneVerifier(props, webClientBuilder, serviceSid, verifyBaseUrl);
    }

    @Bean
    @ConditionalOnMissingBean(PhoneVerifier.class)
    public PhoneVerifier devNoopPhoneVerifier() {
        return new DevNoopPhoneVerifier();
    }
}
