# ADR-004 — Queue and asynchronous processing in PostgreSQL (`SKIP LOCKED`), no broker in the MVP

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

Meta's webhook needs a fast answer and redelivers on failure (duplicates). Processing a message
involves the LLM (seconds) and sending through the Graph API. Expected volume: ≤ 50 orders/day,
a few thousand messages/month, peaks at lunch and dinner. Deployed on a VPS.

## Decision

- **Inbound:** `inbound_event` (raw payload, unique `event_key`, `status`, `attempts`,
  `available_at`). The webhook runs `INSERT ... ON CONFLICT (event_key) DO NOTHING` and
  answers 200.
- **Outbound:** `outbound_message` as a transactional outbox: the answer is written in the
  same transaction that updates the conversation state; a `SenderWorker` sends it and stores
  the `wamid`.
- **Workers:** claim with `SELECT ... FOR UPDATE SKIP LOCKED LIMIT n`, virtual threads,
  `LISTEN/NOTIFY` to wake up without waiting for the next poll.
- **Ordering per conversation:** `pg_try_advisory_xact_lock(hash(tenant_id, wa_id))`; if busy,
  the event goes back with `available_at` a few ms ahead.
- **Retry:** exponential backoff with jitter; after N attempts → `FAILED` (queryable dead
  letter) and the conversation is flagged for a human.
- **Abstraction:** a `JobQueue` interface so the implementation can be swapped.
- **Cache:** no cache server in the MVP; in-memory Caffeine for tenant configuration and
  catalog (invalidated by local events — there is a single process).

## Alternatives considered

| Alternative | Why not now |
|---|---|
| **RabbitMQ** | Good fit (queues, DLQ), but one more service to run and no transaction with the database → we would need an outbox anyway. |
| **Kafka / Redpanda** | Overkill for the volume; heavy to operate on a VPS. |
| **Redis Streams** | One more piece just for the queue; persistence and DLQ less natural. |
| **SQS / Pub/Sub** | Ties us to a cloud provider; deployment is a VPS. |
| **JobRunr / db-scheduler** | Viable, but the need (claim + per-conversation ordering + debounce) is specific; our own code is small. Reconsider if it grows. |
| **In-memory `@Async`** | Loses messages on restart; no durable retry. |

## Consequences

- (+) One less piece to operate; atomicity between state and queue; simple reprocessing.
- (+) Every message is auditable in the database.
- (−) PostgreSQL becomes the bottleneck at high volume (hundreds of msg/s) — far above what is
  expected; the `JobQueue` interface keeps the swap local.
- (−) Periodic cleanup of the queue tables (partitioning or a purge job).
