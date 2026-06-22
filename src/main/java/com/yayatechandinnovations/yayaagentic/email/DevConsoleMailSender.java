package com.yayatechandinnovations.yayaagentic.email;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs the rendered mail to the console. Wired by
 * {@link MailSenderConfig#devConsoleMailSender} as the unconditional
 * fallback whenever no other {@link MailSender} bean is registered —
 * for example when {@code yaya.agentic.email.provider} is unset or
 * points at a sender we haven't wired (the SendGrid adapter is the
 * only typed sender today).
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
            // Reachable today only when provider=sendgrid AND something
            // suppressed the SendGrid bean (e.g. a downstream override
            // bean), or when provider is set to a value we don't
            // recognise. Either way, the operator should know mail is
            // landing in the log instead of being delivered.
            log.warn("[MAIL] yaya.agentic.email.provider={} is configured but no "
                    + "matching MailSender bean is registered; falling back to "
                    + "the dev-console sender. Tokens will land in this log. "
                    + "Check that the provider name matches a registered bean "
                    + "(currently: sendgrid).", requested);
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
