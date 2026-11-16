package com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;

/**
 * Where a sender's templates live (whatsapp-shared-templates-design.md §3).
 * Meta approves a template per WhatsApp Business Account, and a Twilio
 * account holds exactly one: senders registered on the platform account share
 * {@link #PLATFORM}; a sender connected through Embedded Signup uses its
 * workspace's own messaging account.
 */
public final class TemplateAccounts {

    /** Account key of every sender without a messaging account of its own. */
    public static final String PLATFORM = "platform";

    private TemplateAccounts() {}

    public static String keyOf(WhatsAppSender sender) {
        String own = sender.messagingAccountId();
        return own == null || own.isBlank() ? PLATFORM : own;
    }

    public static boolean isPlatform(String accountKey) {
        return PLATFORM.equals(accountKey);
    }

    /** The argument {@code ProviderCredsResolver#resolveForAccount} expects. */
    public static String messagingAccountIdOf(String accountKey) {
        return isPlatform(accountKey) ? null : accountKey;
    }
}
