-- Conversation engine (ADR-002, M5): customers, their conversation, its messages and the cost of
-- every LLM call (ADR-005). All under RLS like V4; the reply worker claims due conversations as
-- the owner role and then works inside the tenant context.

CREATE TABLE contact (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    wa_id      text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_contact_wa_id UNIQUE (tenant_id, wa_id)
);

-- One long-lived conversation per customer and business number. reply_due_at schedules the next
-- bot answer: set ~2 s after each customer message (debounce), leased while the answer is built.
CREATE TABLE conversation (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    contact_id      uuid        NOT NULL REFERENCES contact (id) ON DELETE CASCADE,
    phone_number_id text        NOT NULL,
    state           text        NOT NULL DEFAULT 'BOT',
    paused_until    timestamptz,
    context         jsonb       NOT NULL DEFAULT '{}',
    reply_due_at    timestamptz,
    reply_attempts  integer     NOT NULL DEFAULT 0,
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_conversation_contact UNIQUE (tenant_id, contact_id, phone_number_id),
    CONSTRAINT ck_conversation_state CHECK (state IN ('BOT', 'HUMAN', 'CLOSED'))
);
CREATE INDEX ix_conversation_reply_due ON conversation (reply_due_at) WHERE reply_due_at IS NOT NULL;

-- History the LLM sees. ref is the natural key: msg:<wamid> for customer messages,
-- reply:<conversation>:<wamid> for bot answers.
CREATE TABLE message (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    conversation_id uuid        NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    ref             text        NOT NULL,
    direction       text        NOT NULL,
    author          text        NOT NULL,
    wamid           text,
    type            text        NOT NULL,
    text            text        NOT NULL,
    sent_at         timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_message_ref UNIQUE (conversation_id, ref),
    CONSTRAINT ck_message_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_message_author CHECK (author IN ('CUSTOMER', 'BOT', 'STAFF'))
);
CREATE INDEX ix_message_conversation ON message (conversation_id, sent_at);
CREATE INDEX ix_message_tenant ON message (tenant_id);

CREATE TABLE llm_call (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    conversation_id    uuid REFERENCES conversation (id) ON DELETE SET NULL,
    requested_model    text           NOT NULL,
    model              text           NOT NULL,
    effort             text           NOT NULL,
    stop_reason        text           NOT NULL,
    input_tokens       bigint         NOT NULL,
    output_tokens      bigint         NOT NULL,
    cache_read_tokens  bigint         NOT NULL,
    cache_write_tokens bigint         NOT NULL,
    cost_usd           numeric(12, 6) NOT NULL,
    duration_ms        bigint         NOT NULL,
    created_at         timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX ix_llm_call_tenant ON llm_call (tenant_id, created_at);
CREATE INDEX ix_llm_call_conversation ON llm_call (conversation_id);

ALTER TABLE contact ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contact
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE conversation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON conversation
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE message ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON message
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE llm_call ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON llm_call
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
