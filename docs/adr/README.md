# Architecture Decision Records

Formato: contexto → decisão → alternativas → consequências. Um ADR aceito não é editado para
mudar de ideia; cria-se um novo que o substitui (`Status: Substituído por ADR-0XX`).

| ADR | Título | Status |
|---|---|---|
| [001](ADR-001-integracao-whatsapp.md) | Integração com WhatsApp via Cloud API oficial, com coexistência | Aceito |
| [002](ADR-002-motor-de-conversa.md) | Motor de conversa híbrido: fluxos + LLM com ferramentas | Aceito |
| [003](ADR-003-multi-tenancy.md) | Multi-tenancy com `tenant_id` + RLS; segmentos como pacotes | Aceito |
| [004](ADR-004-fila-processamento-assincrono.md) | Fila no PostgreSQL (`SKIP LOCKED`), sem broker no MVP | Aceito |
| [005](ADR-005-provedor-llm.md) | LLM atrás de porta própria; começar com Claude | Aceito |
| [006](ADR-006-base-de-conhecimento.md) | Base de conhecimento: ferramentas + prompt; RAG com pgvector depois | Aceito |
| [007](ADR-007-monolito-modular.md) | Monólito modular, Maven multi-módulo, Java 21, Spring Boot 4.1 | Aceito |
| [008](ADR-008-transbordo-humano.md) | Transbordo pelo app WhatsApp Business com pausa por eco | Aceito |
| [009](ADR-009-frontend-repositorio-separado.md) | Frontend em repositório separado do backend | Aceito |
