package com.yayatechandinnovations.yayaagentic.outreach.resolvers;

import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.outreach.RecipientResolver;
import com.yayatechandinnovations.yayaagentic.outreach.ResolvedTarget;
import com.yayatechandinnovations.yayaagentic.persistence.RoleMemberEntity;
import com.yayatechandinnovations.yayaagentic.persistence.RoleMemberRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * "All members of role X" — fans out to each member's chat (§6, SM-3). A
 * shift-wide closure report reaches every on-duty operator; each replies
 * independently and each callback routes to its own correlation. Membership is
 * the platform-managed {@code role_members} roster (§18.3 v1 choice).
 *
 * <p>Empty roster ⇒ empty list ⇒ silent drop, same existence-hiding rule as
 * every resolver.
 */
@Component
public class ByRoleRecipientResolver implements RecipientResolver {

    private final RoleMemberRepository members;

    public ByRoleRecipientResolver(RoleMemberRepository members) {
        this.members = members;
    }

    @Override
    public boolean supports(OutreachRecipient recipient) {
        return recipient instanceof OutreachRecipient.ByRole;
    }

    @Override
    public List<ResolvedTarget> resolve(OutreachRecipient recipient, SystemSender sender) {
        OutreachRecipient.ByRole r = (OutreachRecipient.ByRole) recipient;
        return members.findByTenantIdAndRole(sender.tenant().value(), r.role()).stream()
                .map(RoleMemberEntity::getSubject)
                .map(subject -> new ResolvedTarget(
                        new OutreachRecipient.ByPrincipal(subject).key(), subject, sender.entryPoint()))
                .toList();
    }
}
