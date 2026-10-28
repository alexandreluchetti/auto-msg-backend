# ADR-009 — Frontend em repositório separado do backend

- **Status:** Aceito
- **Data:** 2026-10-28

## Contexto

O MVP inclui um painel de pedidos para a equipe do restaurante, e a fase 2 traz painel de
conversas e editor de base de conhecimento. A diretriz do projeto é manter frontend e backend
(+ IA) em repositórios distintos.

## Decisão

- Dois repositórios dentro da pasta de trabalho `auto-msg/`:
  - `auto-msg-backend` — este repositório: plataforma, integrações WhatsApp e LLM, API.
  - `auto-msg-frontend` — painel web; criado no M8, quando houver o primeiro código de UI.
- O backend expõe **API REST versionada** (`/api/v1/...`) e **SSE** para eventos em tempo real.
  O contrato é publicado em **OpenAPI** e é a fronteira entre os repositórios.
- CORS restrito à origem do painel; autenticação da equipe por tenant (detalhe no M8).
- Substitui a ideia inicial de painel em Thymeleaf + htmx dentro do `platform-app`.

## Alternativas consideradas

- **Thymeleaf + htmx no backend:** menos peças e nenhum build de frontend, mas mistura UI com a
  plataforma e vai contra a diretriz de separação.
- **Monorepo com backend e frontend:** um só lugar para versionar, mas ciclos de build e deploy
  acoplados.

## Consequências

- (+) Ciclos de deploy independentes; o frontend pode evoluir (ou ser trocado) sem tocar a plataforma.
- (+) A API pronta para o painel também serve integrações futuras.
- (−) Contrato de API precisa ser versionado e mantido em sincronia (OpenAPI + testes de contrato).
- (−) Mais um artefato para publicar (estático atrás do mesmo proxy TLS na VPS).
