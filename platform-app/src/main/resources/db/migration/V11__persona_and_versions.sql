-- Humanized service, step H2 (ADR-011, D-013): versioned prompt templates and versioned tenant
-- configuration (draft and published), and the "assistant" block renamed to "persona".

-- A segment's default template has tenant_id NULL and is readable by every tenant; a tenant may
-- have its own override. At most one published and one draft per (segment, tenant).
CREATE TABLE prompt_template (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    segment_id   text        NOT NULL,
    tenant_id    uuid REFERENCES tenant (id) ON DELETE CASCADE,
    version      integer     NOT NULL,
    body         text        NOT NULL,
    status       text        NOT NULL,
    note         text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    CONSTRAINT ck_prompt_template_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);
CREATE UNIQUE INDEX uk_prompt_template_version
    ON prompt_template (segment_id, coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), version);
CREATE UNIQUE INDEX uk_prompt_template_live
    ON prompt_template (segment_id, coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), status)
    WHERE status IN ('DRAFT', 'PUBLISHED');

ALTER TABLE prompt_template ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prompt_template
    USING (tenant_id IS NULL OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- tenant.config stays the published configuration (what WhatsApp uses); versions keep history and
-- the draft the simulator runs.
CREATE TABLE tenant_config_version (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    version      integer     NOT NULL,
    config       jsonb       NOT NULL,
    status       text        NOT NULL,
    created_by   uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    CONSTRAINT uk_tenant_config_version UNIQUE (tenant_id, version),
    CONSTRAINT ck_tenant_config_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);
CREATE UNIQUE INDEX uk_tenant_config_live ON tenant_config_version (tenant_id, status)
    WHERE status IN ('DRAFT', 'PUBLISHED');

ALTER TABLE tenant_config_version ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_config_version
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- "assistant" {name, tone} becomes "persona" (the new required fields come from the pack defaults).
UPDATE tenant
SET config = (config - 'assistant') || jsonb_build_object('persona', config -> 'assistant')
WHERE config ? 'assistant';

INSERT INTO tenant_config_version (tenant_id, version, config, status, published_at)
SELECT id, 1, config, 'PUBLISHED', now() FROM tenant;
