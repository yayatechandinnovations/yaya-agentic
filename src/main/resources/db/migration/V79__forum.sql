-- Community forum (chat-experience-design.md §8): a per-entry-point public
-- feed. Verified accounts and trusted integration senders post; single-level
-- replies, fixed-set emoji reactions, reports with auto-hide, operator
-- moderation. Forum content NEVER feeds an LLM prompt in v1 (§8.2).

CREATE TABLE forum_settings (
    entry_point_id VARCHAR(64) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL,
    enabled        BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE forum_posts (
    id              VARCHAR(64)  PRIMARY KEY,          -- fp_<uuid>
    tenant_id       VARCHAR(64)  NOT NULL,
    entry_point_id  VARCHAR(64)  NOT NULL,
    author_kind     VARCHAR(10)  NOT NULL,             -- USER | SYSTEM | OPERATOR
    account_id      UUID,                              -- USER authors
    sender_id       VARCHAR(64),                       -- SYSTEM authors
    author_display  VARCHAR(120) NOT NULL,
    body            TEXT         NOT NULL,
    reply_to        VARCHAR(64),                       -- single-level threading
    pinned          BOOLEAN      NOT NULL DEFAULT FALSE,
    hidden          BOOLEAN      NOT NULL DEFAULT FALSE,
    reply_count     INT          NOT NULL DEFAULT 0,
    reaction_counts JSONB        NOT NULL DEFAULT '{}'::jsonb,
    report_count    INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    edited_at       TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ
);

-- Feed page: top-level, visible, newest-first (pinned bubble to the top).
CREATE INDEX forum_posts_feed_idx
    ON forum_posts (entry_point_id, created_at DESC)
    WHERE reply_to IS NULL AND deleted_at IS NULL;
CREATE INDEX forum_posts_replies_idx ON forum_posts (reply_to, created_at);

CREATE TABLE forum_reactions (
    post_id    VARCHAR(64) NOT NULL REFERENCES forum_posts (id) ON DELETE CASCADE,
    account_id UUID        NOT NULL,
    emoji      VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, account_id, emoji)
);

CREATE TABLE forum_reports (
    id          VARCHAR(64)  PRIMARY KEY,              -- fr_<uuid>
    post_id     VARCHAR(64)  NOT NULL REFERENCES forum_posts (id) ON DELETE CASCADE,
    account_id  UUID         NOT NULL,
    reason      VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolution  VARCHAR(20)                            -- DISMISSED | UPHELD
);

CREATE UNIQUE INDEX forum_reports_once_per_account
    ON forum_reports (post_id, account_id) WHERE resolved_at IS NULL;
CREATE INDEX forum_reports_open_idx ON forum_reports (created_at) WHERE resolved_at IS NULL;
