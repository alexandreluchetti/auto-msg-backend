-- Human pace (ADR-011, step H3): an answer is a turn of outbox rows (typing indicator, then up to
-- two messages) scheduled with available_at; turn_id groups them so a new customer message can
-- cancel the rows still pending (H4).
ALTER TABLE outbound_message DROP CONSTRAINT ck_outbound_message_kind;
ALTER TABLE outbound_message ADD CONSTRAINT ck_outbound_message_kind
    CHECK (kind IN ('TEXT', 'TEMPLATE', 'BUTTONS', 'LIST', 'READ', 'TYPING'));

ALTER TABLE outbound_message ADD COLUMN turn_id uuid;
ALTER TABLE outbound_message ADD COLUMN turn_seq integer;
CREATE INDEX ix_outbound_message_turn ON outbound_message (turn_id) WHERE turn_id IS NOT NULL;

ALTER TABLE outbound_message DROP CONSTRAINT IF EXISTS ck_outbound_message_status;
ALTER TABLE outbound_message ADD CONSTRAINT ck_outbound_message_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'CANCELLED'));
