-- Outbound outbox (ADR-004): one row per message or read receipt to send. idempotency_key makes
-- reprocessing an inbound event harmless. tenant_id is filled once tenants exist (M3).
CREATE TABLE outbound_message (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key  text        NOT NULL,
    tenant_id        uuid REFERENCES tenant (id),
    phone_number_id  text        NOT NULL,
    wa_id            text        NOT NULL,
    kind             text        NOT NULL,
    payload          jsonb       NOT NULL,
    status           text        NOT NULL DEFAULT 'PENDING',
    attempts         integer     NOT NULL DEFAULT 0,
    available_at     timestamptz NOT NULL DEFAULT now(),
    created_at       timestamptz NOT NULL DEFAULT now(),
    sent_at          timestamptz,
    wamid            text,
    delivery_status  text,
    pricing_category text,
    billable         boolean,
    last_error       text,
    CONSTRAINT uk_outbound_message_key UNIQUE (idempotency_key),
    CONSTRAINT uk_outbound_message_wamid UNIQUE (wamid),
    CONSTRAINT ck_outbound_message_kind CHECK (kind IN ('TEXT', 'TEMPLATE', 'READ')),
    CONSTRAINT ck_outbound_message_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    CONSTRAINT ck_outbound_message_delivery CHECK (delivery_status IN ('sent', 'delivered', 'read', 'failed'))
);

-- Sender claim: due rows, including PROCESSING rows whose lease expired (crashed sender).
CREATE INDEX ix_outbound_message_due ON outbound_message (available_at)
    WHERE status IN ('PENDING', 'PROCESSING');

-- 24h customer service window: latest message of each customer (M3 moves it to conversation).
CREATE INDEX ix_inbound_event_customer_message ON inbound_event (phone_number_id, wa_id)
    WHERE event_type = 'MESSAGE';
