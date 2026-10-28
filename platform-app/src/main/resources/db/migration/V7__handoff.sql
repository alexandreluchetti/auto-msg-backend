-- Human handoff (ADR-008, M6). A conversation in HUMAN is paused until paused_until; the bot takes
-- it back on the next customer message after that. failure_streak counts consecutive answers the
-- bot could not give, to hand over after too many.
ALTER TABLE contact ADD COLUMN display_name text;

ALTER TABLE conversation ADD COLUMN handoff_reason text;
ALTER TABLE conversation ADD COLUMN handoff_at timestamptz;
ALTER TABLE conversation ADD COLUMN failure_streak integer NOT NULL DEFAULT 0;
ALTER TABLE conversation ADD CONSTRAINT ck_conversation_handoff_reason
    CHECK (handoff_reason IN ('STAFF_REPLY', 'CUSTOMER_REQUEST', 'BOT_REQUEST', 'REPEATED_FAILURES'));
