package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatEntryPointRepository extends JpaRepository<ChatEntryPointEntity, String> {

    List<ChatEntryPointEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<ChatEntryPointEntity> findByTenantIdAndId(String tenantId, String id);

    /** Public front-door lookup. The handle is the tenant-agnostic capability in
     *  the QR / deep link, so resolution must cross workspaces — the entry point
     *  carries its own tenant. */
    @WorkspaceUnscoped("handle is the globally-unique public capability; the front door has no tenant context yet")
    Optional<ChatEntryPointEntity> findByHandle(String handle);

    /** Trimmed public projection for the directory (chat-public-directory-design.md §4).
     *  Never carries auth/abuse/session policy, internal id aside from the cursor key. */
    interface DirectoryCardView {
        String getId();
        String getHandle();
        String getDisplayName();
        String getLogoUrl();
        String getAccentColorHex();
        String getTagline();
        String getAuthMode();
    }

    /**
     * Keyset page of the public directory (chat-public-directory-design.md §4.2). Only
     * {@code ACTIVE + discoverable} rows — the partial indexes {@code chat_dir_browse_idx}
     * / {@code chat_dir_search_idx} exactly cover this predicate, so the scan is bounded
     * to the hot set. Ordered by {@code (display_name, id)}; the caller passes the last
     * row's key as the cursor and fetches {@code limit+1} to detect a next page.
     *
     * <p>Every nullable parameter is {@code CAST(... AS text)} so Postgres can type the
     * bind even when it is null (first page / no query).
     */
    @WorkspaceUnscoped("public directory is a cross-tenant catalog of opted-in entry points; no operator context")
    @Query(value = """
            SELECT id                AS "id",
                   handle            AS "handle",
                   display_name      AS "displayName",
                   logo_url          AS "logoUrl",
                   accent_color_hex  AS "accentColorHex",
                   tagline           AS "tagline",
                   auth_mode         AS "authMode"
            FROM chat_entry_points
            WHERE status = 'ACTIVE' AND discoverable
              AND (CAST(:like AS text) IS NULL OR search_text LIKE CAST(:like AS text))
              AND (CAST(:cursorName AS text) IS NULL
                   OR display_name > CAST(:cursorName AS text)
                   OR (display_name = CAST(:cursorName AS text) AND id > CAST(:cursorId AS text)))
            ORDER BY display_name, id
            LIMIT :limit
            """, nativeQuery = true)
    List<DirectoryCardView> searchDirectory(@Param("like") String like,
                                            @Param("cursorName") String cursorName,
                                            @Param("cursorId") String cursorId,
                                            @Param("limit") int limit);
}
