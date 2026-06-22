package com.yayatechandinnovations.yayaagentic.email;

import com.sendgrid.Response;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the SendGrid adapter through its functional-Api seam so the
 * tests never touch the network. Focused on the contract that
 * {@link MailOutboxDrainer} relies on: 2xx → ok, 429/5xx → transient,
 * other 4xx → permanent, I/O → transient.
 */
class SendGridMailSenderTest {

    private static final MailMessage MSG = new MailMessage(
            "user@example.com",
            "Hello",
            "Plain body",
            null,
            "verify-email",
            "idem-key");

    @Test
    void status_202_returns_normally() {
        var sender = new SendGridMailSender(stub(202, "{}"), props());
        sender.send(MSG);
        // No throw → success path.
    }

    @Test
    void status_429_is_transient() {
        var sender = new SendGridMailSender(stub(429, "rate limited"), props());
        assertThatThrownBy(() -> sender.send(MSG))
                .isInstanceOf(MailSendException.class)
                .matches(ex -> ((MailSendException) ex).isTransient(), "isTransient");
    }

    @Test
    void status_500_is_transient() {
        var sender = new SendGridMailSender(stub(500, "boom"), props());
        assertThatThrownBy(() -> sender.send(MSG))
                .isInstanceOfSatisfying(MailSendException.class,
                        ex -> assertThat(ex.isTransient()).isTrue());
    }

    @Test
    void status_400_is_permanent() {
        var sender = new SendGridMailSender(stub(400, "bad payload"), props());
        assertThatThrownBy(() -> sender.send(MSG))
                .isInstanceOfSatisfying(MailSendException.class,
                        ex -> assertThat(ex.isTransient()).isFalse());
    }

    @Test
    void status_403_is_permanent() {
        var sender = new SendGridMailSender(stub(403, "forbidden"), props());
        assertThatThrownBy(() -> sender.send(MSG))
                .isInstanceOfSatisfying(MailSendException.class,
                        ex -> assertThat(ex.isTransient()).isFalse());
    }

    @Test
    void io_error_is_transient() {
        var sender = new SendGridMailSender(
                req -> { throw new IOException("connection reset"); },
                props());
        assertThatThrownBy(() -> sender.send(MSG))
                .isInstanceOfSatisfying(MailSendException.class,
                        ex -> assertThat(ex.isTransient()).isTrue());
    }

    @Test
    void blank_api_key_refuses_to_boot() {
        // Production ctor (the one without the Api seam) goes through
        // requireApiKey and must fail fast when the key is missing.
        YayaAgenticProperties propsWithoutKey = new YayaAgenticProperties(
                null, null, null, null, null, null, null, null, null, null,
                new YayaAgenticProperties.Email(
                        true, "sendgrid", "noreply@example.com", null,
                        new YayaAgenticProperties.Email.SendGrid(""),
                        YayaAgenticProperties.Email.Outbox.defaults()));
        assertThatThrownBy(() -> new SendGridMailSender(propsWithoutKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SENDGRID_API_KEY");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static SendGridMailSender.Api stub(int status, String body) {
        return req -> {
            Response r = new Response();
            r.setStatusCode(status);
            r.setBody(body);
            return r;
        };
    }

    private static YayaAgenticProperties props() {
        return new YayaAgenticProperties(
                null, null, null, null, null, null, null, null, null, null,
                new YayaAgenticProperties.Email(
                        true, "sendgrid", "noreply@example.com", "reply@example.com",
                        new YayaAgenticProperties.Email.SendGrid("SG.fake-key"),
                        new YayaAgenticProperties.Email.Outbox(
                                3, Duration.ofSeconds(1), 2.0,
                                Duration.ofSeconds(60), 100)));
    }
}
