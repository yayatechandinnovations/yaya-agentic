package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.yayatechandinnovations.yayaagentic.channel.ProviderCreds;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.WhatsAppMessagingAccount;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.WhatsAppMessagingAccountService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@code whatsapp-channel-design.md} §7.3 resolver, landed: senders
 * provisioned through Meta Embedded Signup carry a messaging-account
 * reference and speak with their dedicated Twilio subaccount's credentials;
 * legacy senders (no reference) keep resolving the platform-singleton
 * credential unchanged. {@code @Primary} supersedes
 * {@link PlatformSingletonTwilioCredsResolver} exactly as that class's
 * Javadoc reserved.
 *
 * <p>Subaccount creds intentionally omit the platform Messaging Service SID —
 * a parent-account Messaging Service is not addressable from a subaccount, so
 * dispatch falls back to {@code From} addressing with the sender's phone.
 */
@Component
@Primary
public class TenantSubaccountTwilioCredsResolver implements ProviderCredsResolver {

    private final PlatformSingletonTwilioCredsResolver platform;
    private final WhatsAppMessagingAccountService accounts;

    public TenantSubaccountTwilioCredsResolver(PlatformSingletonTwilioCredsResolver platform,
                                               WhatsAppMessagingAccountService accounts) {
        this.platform = platform;
        this.accounts = accounts;
    }

    @Override
    public ProviderCreds resolve(WhatsAppSender sender) throws ProviderNotConfiguredException {
        if (sender.messagingAccountId() == null) {
            return platform.resolve(sender);
        }
        WhatsAppMessagingAccount account = accounts.findById(sender.messagingAccountId())
                .orElseThrow(() -> new ProviderNotConfiguredException(
                        "messaging account " + sender.messagingAccountId()
                                + " referenced by sender " + sender.id().value() + " does not exist"));
        // Tenant isolation: a sender can only ever speak with credentials of
        // an account owned by its own tenant. A mismatch means data
        // inconsistency — fail closed, never fall back to another credential.
        if (!account.tenant().equals(sender.tenant())) {
            throw new ProviderNotConfiguredException(
                    "messaging account tenant mismatch for sender " + sender.id().value());
        }
        return subaccountCreds(account);
    }

    @Override
    public ProviderCreds resolveForAccount(String messagingAccountId) throws ProviderNotConfiguredException {
        if (messagingAccountId == null) {
            return platform.resolveForAccount(null);
        }
        WhatsAppMessagingAccount account = accounts.findById(messagingAccountId)
                .orElseThrow(() -> new ProviderNotConfiguredException(
                        "messaging account " + messagingAccountId + " does not exist"));
        return subaccountCreds(account);
    }

    private ProviderCreds subaccountCreds(WhatsAppMessagingAccount account) {
        if (!account.status().isOperational() || account.twilioSubaccountSid() == null) {
            throw new ProviderNotConfiguredException(
                    "messaging account " + account.id() + " is not operational ("
                            + account.status() + ")");
        }
        return TwilioCreds.forSubaccount(
                account.twilioSubaccountSid(),
                accounts.decryptAuthToken(account.id()));
    }

    @Override
    public boolean isReady() {
        return platform.isReady();
    }
}
