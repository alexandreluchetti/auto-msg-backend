# ADR-010 — Enforcing row-level security with `SET LOCAL ROLE` and edge lookup functions

- **Status:** Accepted
- **Date:** 2026-10-28
- **Refines:** ADR-003

## Context

ADR-003 chose a shared schema with `tenant_id` and PostgreSQL Row-Level Security, with "an
application role without `BYPASSRLS`". RLS is skipped for superusers and for a table's owner.
Locally, in tests (Testcontainers) and on the VPS the application connects with the role that owns
the tables, often a superuser (the default user of the `postgres` image). Two login roles (one for
Flyway, one for the app) would protect business tables, but they also need two sets of credentials
everywhere, and the queue workers and platform operations (creating a tenant, resolving the tenant
of a webhook) legitimately work across tenants.

## Decision

- Migrations create a `NOLOGIN` role **`conversa_app`** (no superuser, no `BYPASSRLS`) with DML
  privileges on the schema (also for future tables, via default privileges) and grant it to the
  owner role.
- **Business work runs through `TenantTransactions`**: in its transaction it runs
  `set_config('app.tenant_id', <tenant>, true)` and `SET LOCAL ROLE conversa_app`. From then until
  the transaction ends, RLS applies even if the connection's user is a superuser. Joining a
  transaction already bound to another tenant is refused.
- Policies compare with `nullif(current_setting('app.tenant_id', true), '')::uuid`, so a
  transaction without a tenant matches no row.
- **Cross-tenant work stays on the owner role:** queue workers, tenant creation, the bootstrap.
- **Edge lookups** that must run before a tenant is known (tenant and token reference of a
  `phone_number_id`) are `SECURITY DEFINER` functions with a fixed `search_path`. They return only
  what the edge needs.
- Secrets are never stored in the database: `tenant_channel.access_token_ref` names a property or
  environment variable.

## Alternatives considered

- **Two login roles (Flyway owner + app role):** RLS also covers code that forgets the tenant
  context. Costs a second credential in every environment, and cross-tenant operations need a third
  path. Can be adopted later without changing policies: the app role would be `conversa_app` itself.
- **`FORCE ROW LEVEL SECURITY` on the tables:** makes the owner subject to RLS, but superusers still
  bypass it, so it does not help where the app connects as a superuser.
- **Filtering by `tenant_id` only in queries:** no safety net (rejected in ADR-003).

## Consequences

- (+) Isolation is enforced by the database in every environment, tests included, with one
  connection user.
- (+) Tests prove it: a tenant context sees and changes only its rows; writing another tenant's
  row is refused; the app role without a tenant sees nothing.
- (−) Code that touches business tables outside `TenantTransactions` runs as the owner and is not
  protected. Rule (in `CLAUDE.md`): business repositories are only called inside a tenant context,
  and each new repository gets an isolation test.
- (−) Creating the role needs `CREATEROLE` (or superuser) at migration time; true for the
  PostgreSQL containers we run.
