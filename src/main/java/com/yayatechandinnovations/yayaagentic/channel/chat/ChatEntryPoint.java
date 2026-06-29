package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;

/**
 * A branded, publicly addressable chat surface that pins one tenant profile —
 * the Chat-channel analog of a WhatsApp/SMS sender ({@code chat-channel-design.md} §6).
 *
 * <p>{@code profile} is a convenience mirror of the entry point's {@code MatchAny}
 * channel binding; {@code handle} is the opaque public capability encoded in the
 * QR / deep link.
 */
public record ChatEntryPoint(
        ChatEntryPointId id,
        Ids.TenantId tenant,
        String handle,
        Ids.ProfileId profile,
        Branding branding,
        AuthMode authMode,
        AuthConfig authConfig,
        SessionPolicy sessionPolicy,
        AbusePolicy abuse,
        boolean requiresVerifiedPhone,
        EntryPointStatus status,
        Instant createdAt,
        Instant updatedAt
) {}
