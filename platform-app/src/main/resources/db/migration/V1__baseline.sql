-- Baseline: extensões e a tabela raiz de tenants. Demais tabelas entram nos marcos M1+.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE tenant (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            text        NOT NULL UNIQUE,
    display_name    text        NOT NULL,
    segment_id      text        NOT NULL,
    segment_version integer     NOT NULL,
    config          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status          text        NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
