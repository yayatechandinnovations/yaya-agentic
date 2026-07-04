package com.yayatechandinnovations.yayaagentic.outreach.resolvers;

import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.outreach.RecipientResolver;
import com.yayatechandinnovations.yayaagentic.outreach.ResolvedTarget;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * "This operator" — the primary SM-0 case (§6). Delivers to the chat the
 * verified subject added: one target, keyed by the principal subject, owned by
 * that subject, on the sender's bound entry point.
 *
 * <p>Consent/opt-out gating (a subject who muted this sender) lands with the
 * {@code opt_outs} integration in SM-3; for now every named subject is eligible.
 */
@Component
public class ByPrincipalRecipientResolver implements RecipientResolver {

    @Override
    public boolean supports(OutreachRecipient recipient) {
        return recipient instanceof OutreachRecipient.ByPrincipal;
    }

    @Override
    public List<ResolvedTarget> resolve(OutreachRecipient recipient, SystemSender sender) {
        OutreachRecipient.ByPrincipal p = (OutreachRecipient.ByPrincipal) recipient;
        return List.of(new ResolvedTarget(p.key(), p.subject(), sender.entryPoint()));
    }
}
