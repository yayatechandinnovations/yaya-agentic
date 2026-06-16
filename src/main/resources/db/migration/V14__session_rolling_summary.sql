-- Rolling LLM-backed summary per session (memory option B).
-- Recorder rotates oldest unsummarized turns into summary_text when the
-- unsummarized tail exceeds yaya.agentic.session.memory.rotationThreshold.
-- Rotation runs post-commit; this column trio is the source of truth.

ALTER TABLE sessions
    ADD COLUMN summary_text             TEXT,
    ADD COLUMN summary_through_turn_idx INTEGER,
    ADD COLUMN summary_updated_at       TIMESTAMPTZ;
