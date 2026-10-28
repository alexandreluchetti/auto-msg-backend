-- Staff users of the panel (M8, D-010). Passwords are stored as bcrypt hashes. Signing in happens
-- before any tenant is known, so the lookup by e-mail is a SECURITY DEFINER function like V4's.
CREATE TABLE staff_user (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    email         text        NOT NULL,
    name          text        NOT NULL,
    password_hash text        NOT NULL,
    active        boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_staff_user_email ON staff_user (lower(email));
CREATE INDEX ix_staff_user_tenant ON staff_user (tenant_id);

ALTER TABLE staff_user ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_user
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION staff_login(p_email text)
    RETURNS TABLE (id uuid, tenant_id uuid, name text, password_hash text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT s.id, s.tenant_id, s.name, s.password_hash
    FROM staff_user s JOIN tenant t ON t.id = s.tenant_id
    WHERE lower(s.email) = lower(p_email) AND s.active AND t.status = 'ACTIVE'
$$;

REVOKE EXECUTE ON FUNCTION staff_login(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION staff_login(text) TO conversa_app;
