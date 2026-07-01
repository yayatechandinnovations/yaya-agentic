-- Chat public directory (chat-public-directory-design.md §3). Adds an opt-in
-- discoverability flag + a one-line catalog blurb, and a search-optimized structure
-- so end users can search/filter *published* chats. Only ACTIVE + discoverable entry
-- points ever surface — everything else keeps the §6.1 non-enumeration guarantee.

ALTER TABLE chat_entry_points
    ADD COLUMN discoverable BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN tagline      VARCHAR(140);

-- Trigram matching for consumer "search as you type" (substring + typo tolerance) on the
-- name/tagline. Chosen over FTS because directory search is short/partial/prefix-y (§4.3).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Normalized search text: name + tagline, lower-cased. Generated so it can never drift
-- from the source columns.
ALTER TABLE chat_entry_points
    ADD COLUMN search_text TEXT
    GENERATED ALWAYS AS (
        lower(coalesce(display_name, '') || ' ' || coalesce(tagline, ''))
    ) STORED;

-- The catalog is a fraction of all entry points (only opted-in, active ones) and is the
-- only thing the public endpoint reads. PARTIAL indexes bound index size + I/O to exactly
-- that hot set, and let browse pages be index-only range scans over the keyset.
CREATE INDEX chat_dir_browse_idx
    ON chat_entry_points (display_name, id)
    WHERE status = 'ACTIVE' AND discoverable;

CREATE INDEX chat_dir_search_idx
    ON chat_entry_points USING gin (search_text gin_trgm_ops)
    WHERE status = 'ACTIVE' AND discoverable;
