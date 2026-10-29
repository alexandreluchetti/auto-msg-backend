-- Memory (ADR-011, step H5): a rolling summary of the earlier part of long conversations, so the
-- assistant sees summary + latest messages. The customer profile is not a table: it is read from the
-- contact and its latest orders, which retention and erasure already clean.
CREATE TABLE conversation_summary (
    conversation_id uuid PRIMARY KEY REFERENCES conversation (id) ON DELETE CASCADE,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    summary         text        NOT NULL,
    covers_until    timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_conversation_summary_tenant ON conversation_summary (tenant_id, updated_at);

ALTER TABLE conversation_summary ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON conversation_summary
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
