# ADR-007 — Modular monolith with Maven multi-module, Java 21 and Spring Boot 4.1

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

One developer, one initial client, deployment on a VPS — but the goal is a multi-segment
product. We need clear boundaries without the operational cost of microservices.

## Decision

- **One deployable** (`platform-app`), split into Maven modules:
  `platform-core`, `channel-whatsapp`, `llm-anthropic`, `segment-restaurant`, `platform-app`.
- `platform-core` in plain Java (no Spring), holding only types and SPIs.
- Adapters and segments depend only on `platform-core`; `platform-app` wires them together.
- **ArchUnit** (`ArchitectureTest`) checks the dependencies in the build.
- Java 21 (LTS, virtual threads), Spring Boot 4.1.x, PostgreSQL 17 + pgvector, Flyway, JPA
  (Hibernate) for aggregates and `JdbcClient` for queues/specific queries.
- Integration tests with **Testcontainers** (`*IT`, run in `mvn verify`).

## Alternatives considered

- **Microservices (separate webhook, engine, sender):** independent scaling we do not need;
  more deployments, networking and distributed consistency.
- **Single module with packages:** simpler, but boundaries only by convention — Maven prevents
  wrong dependencies at compile time.
- **Spring Modulith:** a good option for boundaries *inside* `platform-app`; can be adopted
  later without conflicting with this decision.
- **Gradle:** the team prefers Maven.

## Consequences

- (+) One process to operate; cheap refactoring; explicit boundaries.
- (+) Extracting a module into its own service later is feasible (e.g. `channel-whatsapp`).
- (−) One deployment affects everything; acceptable at this stage.
