package com.yayatechandinnovations.yayaagentic.email;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs the rendered mail to the console. Wired by
 * {@link MailSenderConfig#devConsoleMailSender} as the unconditional
 * fallback whenever no other {@link MailSender} bean is registered.
 * Stays usable even when an operator points
 * {@code yaya.agentic.email.provider} at a sender we haven't wired
 * yet (e.g. {@code sendgrid} pre-M5b).
 *
 * <p>Token-bearing emails (verify, reset, invite) print the raw token
 * in the log so an operator can paste it into the browser. In a real
 * production deployment a typed {@link MailSender} bean takes over and
 * this fallback never runs.</p>
 */
public class DevConsoleMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(DevConsoleMailSender.class);

    private final YayaAgenticProperties props;

    public DevConsoleMailSender(YayaAgenticProperties props) {
        this.props = props;
        warnIfWrongProvider();
    }

    /**
     * Loud one-shot warning at construction when an operator has asked
     * for a non-dev-console provider but we're still running the
     * fallback — tells them the sender they picked isn't wired yet.
     * Runs in the ctor (not @PostConstruct) so it fires before any
     * downstream bean can observe the fallback silently.
     */
    private void warnIfWrongProvider() {
        var email = props.email();
        String requested = email == null || email.provider() == null
                ? "dev-console" : email.provider();
        if (!"dev-console".equalsIgnoreCase(requested)) {
            log.warn("[MAIL] yaya.agentic.email.provider={} is configured but no "
                    + "matching MailSender bean is registered; falling back to "
                    + "the dev-console sender. Tokens will land in this log. "
                    + "(The {} adapter ships in M5b.)", requested, requested);
        }
    }

    @Override
    public String name() { return "dev-console"; }

    @Override
    public void send(MailMessage msg) {
        log.info("[MAIL] template={} to={} subject={}\n----- body -----\n{}\n----- end -----",
                msg.templateId(), msg.to(), msg.subject(), msg.bodyText());
    }
}
