package com.yayatechandinnovations.yayaidentity.signup;

import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TwilioVerifyOtpChannelTest {

    static final UserId USER = new UserId("usr_test");
    static final String PHONE = "+18095551234";

    MockRestServiceServer server;
    TwilioVerifyOtpChannel channel;

    @BeforeEach
    void wire() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        channel = new TwilioVerifyOtpChannel(builder, "https://verify.twilio.test",
                "ACxxx", "secret", "VAyyy");
    }

    @Test
    void startPostsAVerificationWithBasicAuthAndNeverEchoesACode() {
        server.expect(requestTo("https://verify.twilio.test/v2/Services/VAyyy/Verifications"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Basic QUN4eHg6c2VjcmV0"))
                .andExpect(content().formData(form("To", PHONE, "Channel", "sms")))
                .andRespond(withSuccess("{\"status\":\"pending\"}", MediaType.APPLICATION_JSON));

        assertThat(channel.start(USER, PHONE)).isEmpty();
        server.verify();
    }

    @Test
    void approvedCheckIsTrueAnythingElseIsFalse() {
        server.expect(requestTo("https://verify.twilio.test/v2/Services/VAyyy/VerificationCheck"))
                .andExpect(content().formData(form("To", PHONE, "Code", "123456")))
                .andRespond(withSuccess("{\"status\":\"approved\"}", MediaType.APPLICATION_JSON));
        assertThat(channel.check(USER, PHONE, "123456")).isTrue();

        server.reset();
        server.expect(requestTo("https://verify.twilio.test/v2/Services/VAyyy/VerificationCheck"))
                .andRespond(withSuccess("{\"status\":\"pending\"}", MediaType.APPLICATION_JSON));
        assertThat(channel.check(USER, PHONE, "999999")).isFalse();

        // 404 = expired or never-started verification: a plain "no", not an error.
        server.reset();
        server.expect(requestTo("https://verify.twilio.test/v2/Services/VAyyy/VerificationCheck"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(channel.check(USER, PHONE, "123456")).isFalse();
    }

    @Test
    void deliveryFailureSurfacesAsOtpDeliveryException() {
        server.expect(requestTo("https://verify.twilio.test/v2/Services/VAyyy/Verifications"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> channel.start(USER, PHONE))
                .isInstanceOf(SignupService.OtpDeliveryException.class);
    }

    private static org.springframework.util.MultiValueMap<String, String> form(String... kv) {
        var map = new org.springframework.util.LinkedMultiValueMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) {
            map.add(kv[i], kv[i + 1]);
        }
        return map;
    }
}
