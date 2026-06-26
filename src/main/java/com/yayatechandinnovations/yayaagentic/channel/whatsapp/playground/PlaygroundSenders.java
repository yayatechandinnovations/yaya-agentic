package com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;

/**
 * Stable conventions for the playground sender identifier. The pattern lives
 * in code rather than configuration so the outbound dispatcher's "should I
 * call Twilio?" check is a cheap string-startsWith on the hot path.
 */
public final class PlaygroundSenders {

    /** All playground senders share this id prefix. Operator-defined senders
     *  cannot start with {@code snd_playground_sim__} — the registration
     *  service rejects the pattern in {@code WhatsAppSenderService.createDraft}. */
    public static final String PREFIX = "snd_playground_sim__";

    /** E.164 sentinel range for playground senders. Twilio's documented
     *  test number block is +1 555 01XX YYYY (the "0100" prefix is the
     *  reserved range, immune to accidental real-call collisions). The
     *  per-tenant suffix derives from the tenant slug so each workspace
     *  gets a unique row in {@code whatsapp_senders} (the table enforces
     *  global phone uniqueness). */
    public static final String SENTINEL_PHONE_PREFIX = "+15550100";

    private PlaygroundSenders() {}

    public static SenderId forTenant(Ids.TenantId tenant) {
        return new SenderId(PREFIX + tenant.value());
    }

    /** True iff the given sender id is a playground simulator. */
    public static boolean isPlayground(String senderId) {
        return senderId != null && senderId.startsWith(PREFIX);
    }

    /** Stable per-tenant E.164 phone within the playground sentinel range.
     *  Hash mod 10000 to keep within the 4-digit local-number suffix. */
    public static String phoneForTenant(Ids.TenantId tenant) {
        int suffix = Math.abs(tenant.value().hashCode()) % 10000;
        return SENTINEL_PHONE_PREFIX + String.format("%04d", suffix);
    }
}
