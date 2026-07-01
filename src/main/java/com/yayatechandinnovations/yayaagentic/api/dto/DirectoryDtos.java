package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository.DirectoryCardView;

import java.util.List;

/**
 * Wire DTOs for the public chat directory ({@code GET /v1/chat/directory},
 * {@code chat-public-directory-design.md} §4). A {@code DirectoryCard} carries only
 * the public branding an end user needs to render a {@code LOGO | NAME} tile and
 * decide to add it — never the entry-point id, tenant, auth config, abuse policy,
 * or session policy. The internal id used for the keyset cursor is folded into the
 * opaque {@code nextCursor} and never exposed as a field.
 */
public final class DirectoryDtos {

    private DirectoryDtos() {}

    public record DirectoryCard(
            String handle,
            String displayName,
            String logoUrl,
            String accentColorHex,
            String tagline,
            String authMode) {

        public static DirectoryCard of(DirectoryCardView v) {
            return new DirectoryCard(v.getHandle(), v.getDisplayName(), v.getLogoUrl(),
                    v.getAccentColorHex(), v.getTagline(), v.getAuthMode());
        }
    }

    /** {@code nextCursor} is absent (null) on the last page. */
    public record DirectoryPage(List<DirectoryCard> items, String nextCursor) {}
}
