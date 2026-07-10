package com.yayatechandinnovations.yayaidentity.signup;

import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Twilio Verify (v2). Twilio generates, delivers, and stores the code — nothing
 * OTP-shaped touches our database, and {@code start} therefore never returns a
 * raw code to echo. A check answers "approved" exactly once per verification;
 * expired/unknown verifications come back 404, which is simply "no".
 */
public class TwilioVerifyOtpChannel implements OtpChannel {

    private static final Logger log = LoggerFactory.getLogger(TwilioVerifyOtpChannel.class);

    private final RestClient http;
    private final String verifyServiceSid;

    public TwilioVerifyOtpChannel(RestClient.Builder builder, String baseUrl,
                                  String accountSid, String authToken, String verifyServiceSid) {
        this.verifyServiceSid = verifyServiceSid;
        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(accountSid, authToken))
                .build();
    }

    @Override
    public Optional<String> start(UserId user, String phoneE164) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", phoneE164);
        form.add("Channel", "sms");
        try {
            http.post()
                    .uri("/v2/Services/{sid}/Verifications", verifyServiceSid)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.warn("Twilio Verify start failed for {}: HTTP {}", phoneE164, e.getStatusCode().value());
            throw new SignupService.OtpDeliveryException();
        }
        return Optional.empty();
    }

    @Override
    public boolean check(UserId user, String phoneE164, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", phoneE164);
        form.add("Code", code);
        try {
            Map<?, ?> body = http.post()
                    .uri("/v2/Services/{sid}/VerificationCheck", verifyServiceSid)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            return body != null && "approved".equals(body.get("status"));
        } catch (RestClientResponseException e) {
            // 404 = expired or never started; any other failure is also a non-approval.
            if (e.getStatusCode().value() != 404) {
                log.warn("Twilio Verify check failed for {}: HTTP {}", phoneE164, e.getStatusCode().value());
            }
            return false;
        }
    }

    private static String basicAuth(String sid, String token) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((sid + ":" + token).getBytes(StandardCharsets.UTF_8));
    }
}
