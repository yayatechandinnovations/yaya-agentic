package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;
import java.util.Map;

/**
 * The WhatsApp sender aggregate per {@code whatsapp-channel-design.md} §6.2 —
 * the runtime, in-memory view of one row of {@code whatsapp_senders}. The
 * persistence-layer {@code WhatsAppSenderEntity} maps to/from this record at
 * the service boundary; controllers and the engine never see the entity.
 *
 * @param id                    platform-stable, minted at DRAFT, safe as
 *                              Twilio idempotency key
 * @param tenant                owning tenant — never modifiable post-create
 * @param phoneE164             unique platform-wide
 * @param displayName           operator-requested
 * @param approvedDisplayName   null until ACTIVE; may differ from
 *                              {@code displayName} if Meta approves a variant
 * @param business              Meta-required business profile
 * @param status                lifecycle state (§6.1 state machine)
 * @param onboarding            last sync snapshot from the BSP
 * @param webhookSecret         PLAINTEXT secret used by inbound signature
 *                              verification; the persistence layer never
 *                              stores this in the clear — it goes through
 *                              {@code SecretCipher} on the way to the BYTEA
 *                              column
 * @param defaultLocale         BCP-47; used for template selection
 * @param metadata              operator-set tags + per-sender feature
 *                              flags (e.g. {@code transcribeInboundAudio};
 *                              see §9.5 privacy note)
 */
public record WhatsAppSender(
        SenderId           id,
        Ids.TenantId       tenant,
        String             phoneE164,
        String             displayName,
        String             approvedDisplayName,
        BusinessProfile    business,
        SenderStatus       status,
        OnboardingState    onboarding,
        String             webhookSecret,
        String             defaultLocale,
        Map<String, Object> metadata,
        Instant            createdAt,
        Instant            updatedAt,
        Instant            lastSyncAt
) {

    public WhatsAppSender {
        if (id == null) {
            throw new IllegalArgumentException("id required");
        }
        if (tenant == null) {
            throw new IllegalArgumentException("tenant required");
        }
        if (phoneE164 == null || phoneE164.isBlank()) {
            throw new IllegalArgumentException("phoneE164 must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (business == null) {
            throw new IllegalArgumentException("business required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status required");
        }
        onboarding    = onboarding    == null ? OnboardingState.empty() : onboarding;
        defaultLocale = defaultLocale == null || defaultLocale.isBlank() ? "en-US" : defaultLocale;
        metadata      = metadata      == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience: whether an operator can edit this sender's mutable fields. */
    public boolean isEditable() {
        return status.isEditable();
    }
}
