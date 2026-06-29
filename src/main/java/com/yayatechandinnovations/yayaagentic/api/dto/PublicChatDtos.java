package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.chat.AuthConfig;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;

import java.util.List;

/**
 * Wire DTOs for the public chat front door ({@code /v1/chat/*},
 * {@code chat-channel-design.md} §9). These carry only what an end-user client
 * needs to render the {@code LOGO | NAME} tile and decide how to authenticate —
 * never the entry-point id, tenant secrets, abuse policy, or session policy.
 */
public final class PublicChatDtos {

    private PublicChatDtos() {}

    /** Public branding card returned by {@code GET /v1/chat/e/{handle}}. */
    public record EntryPointCard(
            String handle,
            String displayName,
            String logoUrl,
            String accentColorHex,
            String welcomeText,
            String defaultLocale,
            String authMode,        // ANONYMOUS_ONLY | AUTH_REQUIRED | AUTH_OPTIONAL
            boolean paused,
            boolean requiresVerifiedPhone,
            OidcLogin oidc          // non-null only when the app must launch an interactive login
    ) {
        public static EntryPointCard of(ChatEntryPoint e) {
            OidcLogin oidc = (e.authConfig() instanceof AuthConfig.Oidc o)
                    ? new OidcLogin(o.issuer(), o.audience(), o.scopes())
                    : null;
            return new EntryPointCard(
                    e.handle(),
                    e.branding().displayName(),
                    e.branding().logoUrl(),
                    e.branding().accentColorHex(),
                    e.branding().welcomeText(),
                    e.branding().defaultLocale(),
                    e.authMode().name(),
                    e.status() == com.yayatechandinnovations.yayaagentic.channel.chat.EntryPointStatus.PAUSED,
                    e.requiresVerifiedPhone(),
                    oidc);
        }
    }

    /** What the app needs to drive an interactive OIDC (PKCE) login (§8.2). */
    public record OidcLogin(String issuer, String audience, List<String> scopes) {}

    /** Body for {@code POST /v1/chat/e/{handle}/sessions}. The bearer (if any)
     *  rides the {@code Authorization} header, not the body. */
    public record StartRequest(String deviceId, String locale) {}

    public record StartResponse(
            String sessionId,
            String profileId,
            int profileVersion,
            String greeting,
            List<String> quickReplies,
            EntryPointCard entryPoint
    ) {}

    public record MessageRequest(String text) {}

    /** Returned by {@code GET /v1/chat/sessions/{id}} so a restarted app can
     *  confirm its session is still live and re-render the header. */
    public record ResumeResponse(String sessionId, boolean alive, EntryPointCard entryPoint) {}

    /** Result of {@code POST /v1/chat/sessions/{id}/authenticate} (§8.4). */
    public record UpgradeResponse(boolean authenticated, String subject) {}

    /* ---- First-run phone verification (§20) ---- */

    public record VerifyStartRequest(String phoneE164, String name) {}

    public record VerifyStartResponse(String status) {}

    public record VerifyCheckRequest(String phoneE164, String code, String deviceId, String name) {}

    /** On success carries the device pass — a platform-signed token the app
     *  stores and presents as {@code X-Device-Pass} on every chat request. */
    public record VerifyCheckResponse(boolean verified, String devicePass,
                                      String phoneE164, String name) {}
}
