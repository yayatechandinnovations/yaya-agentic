-- Prompt-cache tokens per month, next to the totals they are part of.
-- input_tokens stays the whole prompt; these two say how much of it the provider
-- read from its prompt cache (billed at about a tenth of the input price) and how
-- much it wrote to it (about 1.25x), so a month's real cost can be worked out.
ALTER TABLE tenant_usage_monthly
    ADD COLUMN IF NOT EXISTS cache_read_tokens BIGINT NOT NULL DEFAULT 0;

ALTER TABLE tenant_usage_monthly
    ADD COLUMN IF NOT EXISTS cache_creation_tokens BIGINT NOT NULL DEFAULT 0;

ALTER TABLE profile_usage_monthly
    ADD COLUMN IF NOT EXISTS cache_read_tokens BIGINT NOT NULL DEFAULT 0;

ALTER TABLE profile_usage_monthly
    ADD COLUMN IF NOT EXISTS cache_creation_tokens BIGINT NOT NULL DEFAULT 0;
