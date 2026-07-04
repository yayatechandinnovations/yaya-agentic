package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.chat.AuthConfig;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;

import java.util.List;
import java.util.Map;

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
            String authMechanism,   // NONE | OIDC | RESOLVER — HOW a token is obtained (§8)
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
                    authMechanism(e.authConfig()),
                    e.status() == com.yayatechandinnovations.yayaagentic.channel.chat.EntryPointStatus.PAUSED,
                    e.requiresVerifiedPhone(),
                    oidc);
        }

        /** The client uses this to decide HOW to authenticate: {@code RESOLVER}
         *  starts silently (server resolves from the verified phone); {@code OIDC}
         *  launches interactive login; {@code NONE} needs no token. Orthogonal to
         *  {@code authMode}, which says only WHETHER auth is required. */
        private static String authMechanism(AuthConfig cfg) {
            if (cfg instanceof AuthConfig.ResolverExchange) return "RESOLVER";
            if (cfg instanceof AuthConfig.Oidc) return "OIDC";
            return "NONE";
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
            EntryPointCard entryPoint,
            CommerceInfo commerce   // non-null only when the profile is a store (§3)
    ) {}

    /** The commerce trait the client reads at session start to render cart chrome
     *  the moment a store chat opens (ecommerce-chat-design.md §3). Carries only
     *  what the renderer needs — currency, whether the cart affordance shows, and
     *  the checkout tool id a "Checkout" tap invokes. Null = not a store. */
    public record CommerceInfo(String currency, boolean cartEnabled, String checkoutTool) {
        public static CommerceInfo of(CommerceConfig c) {
            if (c == null) return null;
            String checkoutTool = c.checkoutTool() == null ? null : c.checkoutTool().value();
            return new CommerceInfo(c.currency(), c.cartEnabled(), checkoutTool);
        }
    }

    public record MessageRequest(String text) {}

    /** Body for {@code POST /v1/chat/sessions/{id}/actions} — a client tap
     *  ("Add", "Remove", "Checkout") that runs one whitelisted, clientInvocable
     *  commerce tool through the same executor + Authorizer as a chat message
     *  (the "two doors, one lock" channel, ecommerce-chat-design.md §9). */
    public record ActionRequest(String tool, Map<String, Object> args) {}

    /** Returned by {@code GET /v1/chat/sessions/{id}} so a restarted app can
     *  confirm its session is still live and re-render the header. When
     *  {@code ?history=true}, {@code turns} carries the recorded transcript so the
     *  app can hydrate a resumed conversation (§7.2); null otherwise. */
    public record ResumeResponse(String sessionId, boolean alive, EntryPointCard entryPoint,
                                 List<HistoryTurn> turns) {}

    /** One display turn of a hydrated transcript — user/assistant text only. */
    public record HistoryTurn(String role, String text) {}

    /** Returned by {@code GET /v1/chat/e/{handle}/sessions/latest} (§7.2): the
     *  resume candidate for this account at this chat, and how to treat it.
     *  {@code status} is {@code LIVE} (continue it), {@code ENDED} (show the
     *  transcript, start fresh on next send), or {@code NONE} (no prior session). */
    public record LatestSessionResponse(String status, String sessionId,
                                        String lastActivityAt, int turnCount, String preview) {
        public static LatestSessionResponse none() {
            return new LatestSessionResponse("NONE", null, null, 0, null);
        }
    }

    /** Result of {@code POST /v1/chat/sessions/{id}/authenticate} (§8.4). */
    public record UpgradeResponse(boolean authenticated, String subject) {}

    /* ---- First-run phone verification (§20) ---- */

    /** Phone-first probe: does a completed registration already exist for this
     *  number? The app asks for a name only when {@code registered} is false. */
    public record VerifyLookupRequest(String phoneE164) {}

    public record VerifyLookupResponse(boolean registered) {}

    public record VerifyStartRequest(String phoneE164, String name) {}

    public record VerifyStartResponse(String status) {}

    public record VerifyCheckRequest(String phoneE164, String code, String deviceId, String name) {}

    /** On success carries the device pass — a platform-signed token the app
     *  stores and presents as {@code X-Device-Pass} on every chat request. */
    public record VerifyCheckResponse(boolean verified, String devicePass,
                                      String phoneE164, String name) {}
}
