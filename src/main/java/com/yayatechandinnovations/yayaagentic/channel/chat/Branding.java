package com.yayatechandinnovations.yayaagentic.channel.chat;

/**
 * The end-user-facing skin of an entry point — the {@code LOGO | NAME} tile and
 * chat header ({@code chat-channel-design.md} §6.2). Branding lives on the entry
 * point, not the profile, so one profile can front several differently-branded
 * tiles and rebranding never touches the profile or its versioning.
 *
 * @param displayName   the NAME in {@code LOGO | NAME}, e.g. "Buy Yaya Market"
 * @param logoUrl       the LOGO — a platform-hosted blob or validated https URL
 * @param accentColorHex tile + header theming, e.g. "#0B6E4F" (nullable)
 * @param welcomeText   first bubble shown before the engine greeting; when null
 *                      the profile's intro one-liner is used
 * @param defaultLocale BCP-47; seeds {@code channel.locale} when the client
 *                      sends none
 */
public record Branding(
        String displayName,
        String logoUrl,
        String accentColorHex,
        String welcomeText,
        String defaultLocale
) {
    public Branding {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName required");
        }
        if (logoUrl == null || logoUrl.isBlank()) {
            throw new IllegalArgumentException("logoUrl required");
        }
        if (defaultLocale == null || defaultLocale.isBlank()) {
            defaultLocale = "en-US";
        }
    }
}
