package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;

/**
 * One tenant WABA ↔ one dedicated Twilio subaccount, per the Tech Provider
 * model. The subaccount auth token is deliberately NOT on this record — it
 * stays encrypted at rest and is unwrapped just-in-time by
 * {@link WhatsAppMessagingAccountService#decryptAuthToken}.
 *
 * @param id                  platform-stable ({@code wma_...})
 * @param tenant              owning tenant; a WABA can never span tenants
 * @param wabaId              Meta WhatsApp Business Account id (globally unique)
 * @param twilioSubaccountSid null while PROVISIONING
 */
public record WhatsAppMessagingAccount(
        String                 id,
        Ids.TenantId           tenant,
        String                 wabaId,
        String                 twilioSubaccountSid,
        String                 friendlyName,
        MessagingAccountStatus status,
        Instant                createdAt,
        Instant                updatedAt
) {}
