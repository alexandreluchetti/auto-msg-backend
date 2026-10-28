-- Structured business data (ADR-006), read by tools only: catalog, opening hours and delivery
-- zones. Loaded by spreadsheet import (CSV) until the panel edits them. Every table is under RLS
-- like V4; conversa_app already gets DML on them through the default privileges of V4.

ALTER TABLE tenant ADD COLUMN timezone text NOT NULL DEFAULT 'America/Sao_Paulo';

CREATE TABLE catalog_category (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    name       text        NOT NULL,
    position   integer     NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_catalog_category_name UNIQUE (tenant_id, name)
);

-- code is the business's own item code: imports update items by it, so ids stay stable.
CREATE TABLE catalog_item (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid          NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    category_id uuid          NOT NULL REFERENCES catalog_category (id) ON DELETE CASCADE,
    code        text          NOT NULL,
    name        text          NOT NULL,
    description text,
    price       numeric(10, 2) NOT NULL,
    available   boolean       NOT NULL DEFAULT true,
    position    integer       NOT NULL,
    attributes  jsonb         NOT NULL DEFAULT '{}',
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_catalog_item_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_catalog_item_price CHECK (price >= 0)
);
CREATE INDEX ix_catalog_item_category ON catalog_item (category_id);

CREATE TABLE catalog_option (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    item_id     uuid           NOT NULL REFERENCES catalog_item (id) ON DELETE CASCADE,
    group_name  text           NOT NULL,
    name        text           NOT NULL,
    price_delta numeric(10, 2) NOT NULL DEFAULT 0,
    position    integer        NOT NULL,
    CONSTRAINT uk_catalog_option UNIQUE (item_id, group_name, name)
);
CREATE INDEX ix_catalog_option_tenant ON catalog_option (tenant_id);

-- ISO day of week (1 = Monday). A closing time at or before the opening time ends on the next day.
CREATE TABLE business_hours (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid     NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    day_of_week smallint NOT NULL,
    opens       time     NOT NULL,
    closes      time     NOT NULL,
    CONSTRAINT ck_business_hours_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_business_hours_period CHECK (opens <> closes)
);
CREATE INDEX ix_business_hours_tenant ON business_hours (tenant_id);

CREATE TABLE delivery_zone (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    neighborhood text           NOT NULL,
    fee          numeric(10, 2) NOT NULL,
    position     integer        NOT NULL,
    CONSTRAINT ck_delivery_zone_fee CHECK (fee >= 0)
);
CREATE UNIQUE INDEX uk_delivery_zone_neighborhood ON delivery_zone (tenant_id, lower(neighborhood));

ALTER TABLE catalog_category ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog_category
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE catalog_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog_item
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE catalog_option ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog_option
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE business_hours ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON business_hours
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE delivery_zone ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON delivery_zone
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
