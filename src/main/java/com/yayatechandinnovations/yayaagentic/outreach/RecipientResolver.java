package com.yayatechandinnovations.yayaagentic.outreach;

import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;

import java.util.List;

/**
 * SPI #13 — resolves a logical {@link OutreachRecipient} to concrete delivery
 * targets (the users' established chats). See {@code
 * system-messaging-design.md} §6, §19.
 *
 * <p>Resolution is consent- and opt-out-aware: ineligible targets are silently
 * dropped (an empty list), never surfaced — existence is not leaked (§5.8 rule).
 * A {@link OutreachRecipient.ByRole} may fan out to many targets; {@link
 * OutreachRecipient.ByPrincipal} resolves to one.
 */
public interface RecipientResolver {

    /** Whether this resolver handles the given recipient kind. */
    boolean supports(OutreachRecipient recipient);

    /** Resolve to zero or more delivery targets (empty = nobody eligible). */
    List<ResolvedTarget> resolve(OutreachRecipient recipient, SystemSender sender);
}
