-- End-to-end orders (M7, D-004..D-006). A checkout is the summary shown to the customer with a
-- confirmation button; only the customer's click turns the latest pending checkout into an order.
-- Every table is under RLS like V4.

ALTER TABLE outbound_message DROP CONSTRAINT ck_outbound_message_kind;
ALTER TABLE outbound_message ADD CONSTRAINT ck_outbound_message_kind
    CHECK (kind IN ('TEXT', 'TEMPLATE', 'BUTTONS', 'READ'));

-- Id of the button a customer tapped (interactive button_reply), e.g. confirm:<checkout id>.
ALTER TABLE message ADD COLUMN reply_id text;

CREATE TABLE checkout (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    conversation_id uuid        NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    draft           jsonb       NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING',
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_checkout_status CHECK (status IN ('PENDING', 'CONFIRMED', 'SUPERSEDED'))
);
CREATE INDEX ix_checkout_conversation ON checkout (conversation_id, status);

-- Order numbers are sequential per tenant, shown to the customer and staff (#42).
CREATE TABLE order_sequence (
    tenant_id   uuid PRIMARY KEY REFERENCES tenant (id) ON DELETE CASCADE,
    last_number integer NOT NULL
);

CREATE TABLE customer_order (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    number             integer        NOT NULL,
    conversation_id    uuid           NOT NULL REFERENCES conversation (id),
    contact_id         uuid           NOT NULL REFERENCES contact (id),
    checkout_id        uuid           NOT NULL REFERENCES checkout (id),
    confirmation_wamid text           NOT NULL,
    fulfillment        text           NOT NULL,
    address            text,
    neighborhood       text,
    delivery_fee       numeric(10, 2) NOT NULL,
    payment_method     text           NOT NULL,
    change_for         numeric(10, 2),
    subtotal           numeric(10, 2) NOT NULL,
    total              numeric(10, 2) NOT NULL,
    status             text           NOT NULL DEFAULT 'RECEIVED',
    created_at         timestamptz    NOT NULL DEFAULT now(),
    updated_at         timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uk_customer_order_number UNIQUE (tenant_id, number),
    CONSTRAINT uk_customer_order_checkout UNIQUE (checkout_id),
    CONSTRAINT uk_customer_order_confirmation UNIQUE (tenant_id, confirmation_wamid),
    CONSTRAINT ck_customer_order_fulfillment CHECK (fulfillment IN ('DELIVERY', 'PICKUP')),
    CONSTRAINT ck_customer_order_status CHECK (status IN ('RECEIVED', 'PREPARING', 'OUT_FOR_DELIVERY',
                                                          'READY_FOR_PICKUP', 'COMPLETED', 'CANCELLED'))
);
CREATE INDEX ix_customer_order_tenant ON customer_order (tenant_id, created_at);

CREATE TABLE order_item (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    order_id        uuid           NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    position        integer        NOT NULL,
    catalog_item_id uuid,
    code            text           NOT NULL,
    name            text           NOT NULL,
    quantity        integer        NOT NULL,
    unit_price      numeric(10, 2) NOT NULL,
    options         jsonb          NOT NULL DEFAULT '[]',
    notes           text,
    line_total      numeric(10, 2) NOT NULL,
    CONSTRAINT ck_order_item_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_order_item_order ON order_item (order_id);

ALTER TABLE checkout ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON checkout
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE order_sequence ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON order_sequence
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE customer_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_order
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE order_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON order_item
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
