-- Forum user-blocking (Apple App Store Guideline 1.2 UGC requirement): a member
-- can block another member so that member's posts and replies disappear from the
-- blocker's forum feed. Block is scoped per entry point (the forum is), one-way,
-- and keyed by account. blocked_display is cached so the "blocked members"
-- management list needs no join back to a post.
CREATE TABLE forum_blocks (
    id                 VARCHAR(64)  PRIMARY KEY,
    tenant_id          VARCHAR(64)  NOT NULL,
    entry_point_id     VARCHAR(64)  NOT NULL,
    blocker_account_id UUID         NOT NULL,
    blocked_account_id UUID         NOT NULL,
    blocked_display    VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (entry_point_id, blocker_account_id, blocked_account_id)
);

CREATE INDEX idx_forum_blocks_blocker
    ON forum_blocks (entry_point_id, blocker_account_id);
