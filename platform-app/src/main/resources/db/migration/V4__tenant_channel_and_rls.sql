-- Tenant isolation (ADR-003, ADR-010). Business work runs under the conversa_app role, which
-- does not bypass row-level security, with app.tenant_id set for the transaction:
--   SELECT set_config('app.tenant_id', '<uuid>', true); SET LOCAL ROLE conversa_app;
-- The owner role (migrations, queue workers, platform operations) keeps bypassing RLS.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'conversa_app') THEN
        CREATE ROLE conversa_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
    END IF;
END
$$;
GRANT conversa_app TO CURRENT_USER;

-- Business numbers of a tenant. phone_number_id identifies the tenant of every webhook event.
CREATE TABLE tenant_channel (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenant (id),
    channel          text        NOT NULL DEFAULT 'WHATSAPP',
    waba_id          text,
    phone_number_id  text        NOT NULL,
    access_token_ref text,
    coexistence      boolean     NOT NULL DEFAULT true,
    status           text        NOT NULL DEFAULT 'ACTIVE',
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_tenant_channel_phone_number UNIQUE (phone_number_id),
    CONSTRAINT ck_tenant_channel_channel CHECK (channel IN ('WHATSAPP')),
    CONSTRAINT ck_tenant_channel_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
CREATE INDEX ix_tenant_channel_tenant ON tenant_channel (tenant_id);

ALTER TABLE tenant ADD CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED'));

-- Row-level security. An unset app.tenant_id matches nothing.
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
    USING (id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE tenant_channel ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_channel
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Privileges of the application role, now and for tables created by later migrations.
GRANT USAGE ON SCHEMA public TO conversa_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO conversa_app;
REVOKE ALL ON flyway_schema_history FROM conversa_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO conversa_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO conversa_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO conversa_app;

-- Edge lookups by phone_number_id, before any tenant is known. SECURITY DEFINER runs them as the
-- owner, so they see every channel but expose only what the edge needs.
CREATE FUNCTION channel_tenant_id(p_phone_number_id text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT tenant_id FROM tenant_channel WHERE phone_number_id = p_phone_number_id AND status = 'ACTIVE'
$$;

CREATE FUNCTION channel_access_token_ref(p_phone_number_id text) RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT access_token_ref FROM tenant_channel WHERE phone_number_id = p_phone_number_id AND status = 'ACTIVE'
$$;

REVOKE EXECUTE ON FUNCTION channel_tenant_id(text), channel_access_token_ref(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION channel_tenant_id(text), channel_access_token_ref(text) TO conversa_app;

CREATE INDEX ix_inbound_event_tenant ON inbound_event (tenant_id);
CREATE INDEX ix_outbound_message_tenant ON outbound_message (tenant_id);
