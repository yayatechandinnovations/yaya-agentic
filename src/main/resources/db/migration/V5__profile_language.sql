-- M2.5+: per-profile language directive. BCP 47 codes (en, es, en-US, …).
-- Defaults to 'en' so existing rows keep their current behavior.
ALTER TABLE profiles
    ADD COLUMN language VARCHAR(16) NOT NULL DEFAULT 'en';
