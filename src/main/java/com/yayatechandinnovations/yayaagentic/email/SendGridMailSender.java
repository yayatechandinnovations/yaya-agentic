package com.yayatechandinnovations.yayaagentic.email;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Sends mail through SendGrid's HTTPS v3 {@code /mail/send} endpoint.
 * Active only when {@code yaya.agentic.email.provider=sendgrid} — wiring
 * lives in {@link MailSenderConfig#sendGridMailSender}.
 *
 * <p>Failure mapping (per the {@link MailSender} contract):
 * <ul>
 *   <li>2xx — success, no throw.</li>
 *   <li>429 + 5xx — transient: {@link MailSendException} with
 *       {@code isTransient=true} so {@link MailOutboxDrainer} schedules a
 *       retry with exponential backoff.</li>
 *   <li>Other 4xx — permanent: {@code isTransient=false} so the row
 *       DEAD-letters immediately. (Bad address, malformed payload, etc.)</li>
 *   <li>{@link IOException} — transient (network blip).</li>
 * </ul>
 * </p>
 *
 * <p>The {@code Api} seam exists for tests: production wiring passes a
 * lambda that delegates to {@link SendGrid#api(Request)}; tests pass a
 * canned-response function so unit coverage never hits the wire.</p>
 */
public class SendGridMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SendGridMailSender.class);

    /** Functional seam: takes a {@link Request}, returns a {@link Response} or throws. */
    @FunctionalInterface
    public interface Api {
        Response send(Request request) throws IOException;
    }

    private final Api api;
    private final String fromAddress;
    private final String replyToAddress;

    public SendGridMailSender(YayaAgenticProperties props) {
        this(defaultApi(requireApiKey(props)), props);
    }

    /** Test-only ctor — pass a hand-rolled {@link Api}. */
    SendGridMailSender(Api api, YayaAgenticProperties props) {
        this.api = api;
        YayaAgenticProperties.Email email = props.email();
        this.fromAddress = email == null || isBlank(email.from())
                ? "noreply@yaya.local" : email.from();
        this.replyToAddress = email == null || isBlank(email.replyTo())
                ? null : email.replyTo();
    }

    private static Api defaultApi(String apiKey) {
        SendGrid sg = new SendGrid(apiKey);
        return sg::api;
    }

    private static String requireApiKey(YayaAgenticProperties props) {
        YayaAgenticProperties.Email email = props.email();
        String key = email == null || email.sendgrid() == null
                ? null : email.sendgrid().apiKey();
        if (isBlank(key)) {
            throw new IllegalStateException(
                    "yaya.agentic.email.provider=sendgrid but yaya.agentic.email.sendgrid.api-key "
                            + "(SENDGRID_API_KEY) is blank; refusing to boot with a broken MailSender. "
                            + "Either set the key or switch provider back to dev-console.");
        }
        return key;
    }

    @Override
    public String name() { return "sendgrid"; }

    @Override
    public void send(MailMessage msg) throws MailSendException {
        Mail mail = buildMail(msg);
        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint("mail/send");
        try {
            request.setBody(mail.build());
        } catch (IOException ex) {
            // Payload-build failures are programmer errors, not transient.
            throw new MailSendException(
                    "sendgrid: failed to serialise payload: " + ex.getMessage(), ex, false);
        }

        Response response;
        try {
            response = api.send(request);
        } catch (IOException ex) {
            throw new MailSendException(
                    "sendgrid: network error: " + ex.getMessage(), ex, true);
        }

        int status = response.getStatusCode();
        if (status >= 200 && status < 300) {
            log.debug("[MAIL] sendgrid accepted template={} to={} status={}",
                    msg.templateId(), msg.to(), status);
            return;
        }
        boolean isTransient = status == 429 || status >= 500;
        String body = response.getBody() == null ? "" : response.getBody();
        throw new MailSendException(
                "sendgrid: HTTP " + status + " — " + truncate(body, 512),
                isTransient);
    }

    private Mail buildMail(MailMessage msg) {
        Email from = new Email(fromAddress);
        Email to = new Email(msg.to());
        Content content = new Content(
                msg.bodyHtml() != null && !msg.bodyHtml().isBlank() ? "text/html" : "text/plain",
                msg.bodyHtml() != null && !msg.bodyHtml().isBlank() ? msg.bodyHtml() : msg.bodyText());
        Mail mail = new Mail(from, msg.subject(), to, content);
        if (replyToAddress != null) {
            mail.setReplyTo(new Email(replyToAddress));
        }
        return mail;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
