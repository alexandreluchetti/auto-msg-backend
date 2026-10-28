# ADR-003 — Multi-tenancy: shared schema with `tenant_id` + Row-Level Security; segments as packs

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

The product must serve many businesses from many segments, mostly through configuration. One
business's data can never leak to another. At first there will be few tenants, all on one VPS.

## Decision

### Data isolation

- **One database, one schema, a `tenant_id` column** in every business table.
- **PostgreSQL Row-Level Security** as defense in depth:
  `USING (tenant_id = current_setting('app.tenant_id')::uuid)`.
- `TenantContext` is set at the edge (webhook → `phone_number_id`; panel → signed-in user;
  job → the event's tenant) and applied with `SET LOCAL app.tenant_id` at the start of every
  transaction.
- The application role has **no** `BYPASSRLS`; Flyway runs with its own role. Queue tables
  (`inbound_event`, `outbound_message`) have no RLS, since claiming is cross-tenant.
- Integration tests ensure that a query without a tenant set returns zero rows.

### Segments and customization

- A **Segment Pack** (Maven module, e.g. `segment-restaurant`) implements the `SegmentPack`
  SPI of `platform-core` and provides: tools, flows (YAML), prompts, message templates,
  `config-schema.json` and defaults.
- **Tenant** = `segment_id` + `segment_version` + `config` JSONB validated against the pack's
  schema (tone of voice, assistant name, enabled tools, business rules, texts).
- **Precedence:** platform < pack < tenant. The pack version is pinned per tenant so packs can
  evolve without breaking tenants in production.
- Generic entities in the core (catalog, opening hours, service area, FAQ, contacts);
  segment-specific entities in the pack (delivery order, appointment, reservation).

## Alternatives considered

| Alternative | Pros | Cons |
|---|---|---|
| **Schema per tenant** | Strong isolation, backup/restore per tenant | Migrations N times, connection pool per schema, operational complexity too early |
| **Database per tenant** | Maximum isolation | Cost and operations unaffordable for small clients |
| **`tenant_id` only, no RLS** | Simple | One forgotten `WHERE` leaks data; no safety net |
| **Segments in code (if/else or inheritance)** | Fast for the first client | Becomes a custom product per client; defeats the goal |

## Consequences

- (+) One migration for everyone; onboarding a tenant = inserting rows + configuration.
- (+) RLS protects against query bugs.
- (−) `SET LOCAL` must be guaranteed in every transaction (interceptor/aspect + test).
- (−) A very large tenant may need its own database in the future (the model allows extracting
  by `tenant_id`).
