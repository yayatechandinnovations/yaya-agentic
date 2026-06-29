package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.chat.AbusePolicy;
import com.yayatechandinnovations.yayaagentic.channel.chat.AuthConfig;
import com.yayatechandinnovations.yayaagentic.channel.chat.AuthMode;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;

import java.time.Instant;
import java.util.List;

/**
 * Wire DTOs for the operator-facing chat entry point CRUD
 * ({@code /v1/admin/tenants/{tenantId}/chat-entry-points},
 * {@code chat-channel-design.md} §9, §12). The {@code deepLink} is derived from
 * the handle; the admin UI renders the QR from it client-side.
 */
public final class ChatEntryPointDtos {

    private ChatEntryPointDtos() {}

    /** {@code kind} is None | Oidc | ResolverExchange; sibling fields apply per kind. */
    public record AuthConfigDto(String kind, String issuer, String audience,
                                List<String> scopes, Long authResolverId) {
        public static AuthConfigDto of(AuthConfig c) {
            if (c instanceof AuthConfig.Oidc o) {
                return new AuthConfigDto("Oidc", o.issuer(), o.audience(), o.scopes(), null);
            }
            if (c instanceof AuthConfig.ResolverExchange r) {
                return new AuthConfigDto("ResolverExchange", null, null, null, r.authResolverId());
            }
            return new AuthConfigDto("None", null, null, null, null);
        }

        public AuthConfig toDomain() {
            return switch (kind == null ? "None" : kind) {
                case "Oidc" -> new AuthConfig.Oidc(issuer, audience, scopes);
                case "ResolverExchange" -> new AuthConfig.ResolverExchange(authResolverId);
                default -> new AuthConfig.None();
            };
        }
    }

    public record AbusePolicyDto(Integer startsPerDevicePerHour, Integer startsPerIpPerHour,
                                 Integer messagesPerSessionPerHour, Integer anonymousDailyMessageCap,
                                 String challenge) {
        public static AbusePolicyDto of(AbusePolicy a) {
            return new AbusePolicyDto(a.startsPerDevicePerHour(), a.startsPerIpPerHour(),
                    a.messagesPerSessionPerHour(), a.anonymousDailyMessageCap(), a.challenge().name());
        }

        public AbusePolicy toDomain() {
            AbusePolicy d = AbusePolicy.defaults();
            return new AbusePolicy(
                    startsPerDevicePerHour == null ? d.startsPerDevicePerHour() : startsPerDevicePerHour,
                    startsPerIpPerHour == null ? d.startsPerIpPerHour() : startsPerIpPerHour,
                    messagesPerSessionPerHour == null ? d.messagesPerSessionPerHour() : messagesPerSessionPerHour,
                    anonymousDailyMessageCap == null ? d.anonymousDailyMessageCap() : anonymousDailyMessageCap,
                    challenge == null ? d.challenge() : AbusePolicy.Challenge.valueOf(challenge.trim().toUpperCase()));
        }
    }

    public record CreateRequest(
            String profileId,
            Integer profileVersion,
            String displayName,
            String logoUrl,
            String accentColorHex,
            String welcomeText,
            String defaultLocale,
            String authMode,
            AuthConfigDto authConfig,
            AbusePolicyDto abuse,
            Boolean requiresVerifiedPhone) {}

    public record UpdateRequest(
            String displayName,
            String logoUrl,
            String accentColorHex,
            String welcomeText,
            String defaultLocale,
            String authMode,
            AuthConfigDto authConfig,
            AbusePolicyDto abuse,
            Boolean requiresVerifiedPhone) {}

    public record EntryPointResponse(
            String id,
            String tenantId,
            String handle,
            String deepLink,
            String profileId,
            int profileVersion,
            String displayName,
            String logoUrl,
            String accentColorHex,
            String welcomeText,
            String defaultLocale,
            String authMode,
            AuthConfigDto authConfig,
            AbusePolicyDto abuse,
            boolean requiresVerifiedPhone,
            String status,
            Instant createdAt,
            Instant updatedAt) {

        public static EntryPointResponse of(ChatEntryPoint e, String deepLinkBase) {
            return new EntryPointResponse(
                    e.id().value(),
                    e.tenant().value(),
                    e.handle(),
                    deepLinkBase + e.handle(),
                    e.profile().value(),
                    e.profile().version(),
                    e.branding().displayName(),
                    e.branding().logoUrl(),
                    e.branding().accentColorHex(),
                    e.branding().welcomeText(),
                    e.branding().defaultLocale(),
                    e.authMode().name(),
                    AuthConfigDto.of(e.authConfig()),
                    AbusePolicyDto.of(e.abuse()),
                    e.requiresVerifiedPhone(),
                    e.status().name(),
                    e.createdAt(),
                    e.updatedAt());
        }
    }

    public static AuthMode parseAuthMode(String raw) {
        return raw == null || raw.isBlank() ? AuthMode.ANONYMOUS_ONLY : AuthMode.parse(raw);
    }
}
