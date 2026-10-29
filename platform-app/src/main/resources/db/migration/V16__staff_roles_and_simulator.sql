-- Panel settings and simulator (ADR-011 step H8, D-013).
-- Staff roles: admins edit the persona and settings; everyone else runs orders and conversations.
-- Users created before roles are the business owners set up by the bootstrap, so they become admins.
ALTER TABLE staff_user ADD COLUMN role text NOT NULL DEFAULT 'STAFF';
ALTER TABLE staff_user ADD CONSTRAINT ck_staff_user_role CHECK (role IN ('ADMIN', 'STAFF'));
UPDATE staff_user SET role = 'ADMIN';

DROP FUNCTION staff_login(text);
CREATE FUNCTION staff_login(p_email text)
    RETURNS TABLE (id uuid, tenant_id uuid, name text, password_hash text, role text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT s.id, s.tenant_id, s.name, s.password_hash, s.role
    FROM staff_user s JOIN tenant t ON t.id = s.tenant_id
    WHERE lower(s.email) = lower(p_email) AND s.active AND t.status = 'ACTIVE'
$$;
REVOKE EXECUTE ON FUNCTION staff_login(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION staff_login(text) TO conversa_app;

-- Simulator sessions are conversations of their own (history, cart, model costs) that never reach
-- WhatsApp and stay out of the conversation lists and metrics.
ALTER TABLE conversation ADD COLUMN simulated boolean NOT NULL DEFAULT false;
