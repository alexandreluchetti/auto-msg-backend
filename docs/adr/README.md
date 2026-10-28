# Architecture Decision Records

Format: context → decision → alternatives → consequences. An accepted ADR is not edited to
change course; a new one replaces it (`Status: Superseded by ADR-0XX`). Business rules live in
the [decision log](../decisions.md).

| ADR | Title | Status |
|---|---|---|
| [001](ADR-001-whatsapp-integration.md) | WhatsApp integration through the official Cloud API, with coexistence | Accepted |
| [002](ADR-002-conversation-engine.md) | Hybrid conversation engine: flows + LLM with tools | Accepted |
| [003](ADR-003-multi-tenancy.md) | Multi-tenancy with `tenant_id` + RLS; segments as packs | Accepted |
| [004](ADR-004-async-processing-queue.md) | Queue in PostgreSQL (`SKIP LOCKED`), no broker in the MVP | Accepted |
| [005](ADR-005-llm-provider.md) | LLM behind our own port; start with Claude | Accepted |
| [006](ADR-006-knowledge-base.md) | Knowledge base: tools + prompt; RAG with pgvector later | Accepted |
| [007](ADR-007-modular-monolith.md) | Modular monolith, Maven multi-module, Java 21, Spring Boot 4.1 | Accepted |
| [008](ADR-008-human-handoff.md) | Handoff through the WhatsApp Business app, paused by echoes | Accepted |
| [009](ADR-009-separate-frontend-repository.md) | Frontend in a repository separate from the backend | Accepted |
| [010](ADR-010-rls-enforcement.md) | Enforcing RLS with `SET LOCAL ROLE` and edge lookup functions (refines 003) | Accepted |
