-- V51 declared knowledge_blobs.sha256 as CHAR(64), which Postgres stores as
-- bpchar (Types.CHAR). The JPA entity maps String sha256 -> varchar(64), so
-- Hibernate ddl-auto=validate rejects the mismatch at startup. Align the DB to
-- varchar(64). Additive (not an edit to V51) so already-migrated deployments
-- don't hit a Flyway checksum mismatch.
ALTER TABLE knowledge_blobs ALTER COLUMN sha256 TYPE varchar(64);
