-- LGPD (M9, ARCHITECTURE section 13): privacy notice on first contact, erasure requests from the
-- customer ("apagar meus dados") and anonymized contacts. Under RLS like V4.
ALTER TABLE contact ADD COLUMN consent_notice_at timestamptz;
ALTER TABLE contact ADD COLUMN erased_at timestamptz;

CREATE TABLE data_subject_request (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    contact_id      uuid REFERENCES contact (id) ON DELETE SET NULL,
    conversation_id uuid REFERENCES conversation (id) ON DELETE SET NULL,
    kind            text        NOT NULL DEFAULT 'ERASURE',
    status          text        NOT NULL DEFAULT 'PENDING_CONFIRMATION',
    requested_at    timestamptz NOT NULL DEFAULT now(),
    done_at         timestamptz,
    -- Queue rows (inbound_event, outbound_message) are anonymized once the confirmation is sent,
    -- because the 24h window check reads them; until then the request keeps the address.
    phone_number_id text,
    wa_id           text,
    queue_purged_at timestamptz,
    CONSTRAINT ck_dsr_kind CHECK (kind IN ('ERASURE')),
    CONSTRAINT ck_dsr_status CHECK (status IN ('PENDING_CONFIRMATION', 'DONE', 'CANCELLED'))
);
CREATE INDEX ix_dsr_tenant ON data_subject_request (tenant_id, requested_at);

ALTER TABLE data_subject_request ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON data_subject_request
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Retention and erasure look up queue rows by customer.
CREATE INDEX ix_inbound_event_wa_id ON inbound_event (phone_number_id, wa_id);
CREATE INDEX ix_outbound_message_wa_id ON outbound_message (phone_number_id, wa_id);
CREATE INDEX ix_message_created ON message (tenant_id, created_at);
