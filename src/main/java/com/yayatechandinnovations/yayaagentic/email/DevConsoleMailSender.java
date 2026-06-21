package com.yayatechandinnovations.yayaagentic.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Logs the rendered mail to the console — useful in {@code local} and {@code
 * dev} profiles where SendGrid isn't configured. Token-bearing emails
 * (verify, reset, invite) print their raw token to the log so an operator
 * can paste it into the browser.
 *
 * <p>Active by default unless another {@link MailSender} bean is registered
 * OR {@code yaya.agentic.email.provider} is set to anything other than
 * {@code dev-console}.</p>
 */
@Component
@ConditionalOnMissingBean(MailSender.class)
@ConditionalOnProperty(name = "yaya.agentic.email.provider", havingValue = "dev-console", matchIfMissing = true)
public class DevConsoleMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(DevConsoleMailSender.class);

    @Override
    public String name() { return "dev-console"; }

    @Override
    public void send(MailMessage msg) {
        log.info("[MAIL] template={} to={} subject={}\n----- body -----\n{}\n----- end -----",
                msg.templateId(), msg.to(), msg.subject(), msg.bodyText());
    }
}
