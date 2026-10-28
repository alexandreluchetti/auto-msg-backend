-- Fila de entrada do webhook (ADR-004). Um registro por evento atômico; event_key garante idempotência.
-- tenant_id é preenchido a partir de phone_number_id quando a resolução de tenant existir (M3).
CREATE TABLE inbound_event (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_key       text        NOT NULL,
    event_type      text        NOT NULL,
    field           text,
    waba_id         text,
    phone_number_id text,
    wa_id           text,
    tenant_id       uuid REFERENCES tenant (id),
    payload         jsonb       NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING',
    attempts        integer     NOT NULL DEFAULT 0,
    available_at    timestamptz NOT NULL DEFAULT now(),
    received_at     timestamptz NOT NULL DEFAULT now(),
    processed_at    timestamptz,
    last_error      text,
    CONSTRAINT uk_inbound_event_key UNIQUE (event_key),
    CONSTRAINT ck_inbound_event_type CHECK (event_type IN ('MESSAGE', 'STATUS', 'ECHO', 'OTHER')),
    CONSTRAINT ck_inbound_event_status CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED'))
);

-- Claim dos workers: pendentes por ordem de disponibilidade.
CREATE INDEX ix_inbound_event_pending ON inbound_event (available_at) WHERE status = 'PENDING';
