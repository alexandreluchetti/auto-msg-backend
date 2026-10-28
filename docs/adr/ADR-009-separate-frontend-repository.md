# ADR-009 — Frontend in a repository separate from the backend

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

The MVP includes an orders panel for the restaurant staff, and phase 2 brings a conversations
panel and a knowledge base editor. The project guideline is to keep frontend and backend
(+ AI) in separate repositories.

## Decision

- Two repositories inside the `auto-msg/` working folder:
  - `auto-msg-backend` — this repository: platform, WhatsApp and LLM integrations, API.
  - `auto-msg-frontend` — web panel; created in M8, when the first UI code exists.
- The backend exposes a **versioned REST API** (`/api/v1/...`) and **SSE** for real-time
  events. The contract is published as **OpenAPI** and is the boundary between repositories.
- CORS restricted to the panel's origin; staff authentication per tenant (details in M8).
- Replaces the initial idea of a Thymeleaf + htmx panel inside `platform-app`.

## Alternatives considered

- **Thymeleaf + htmx in the backend:** fewer pieces and no frontend build, but mixes UI with
  the platform and goes against the separation guideline.
- **Monorepo with backend and frontend:** one place to version, but coupled build and deploy
  cycles.

## Consequences

- (+) Independent deploy cycles; the frontend can evolve (or be replaced) without touching the
  platform.
- (+) The API built for the panel also serves future integrations.
- (−) The API contract must be versioned and kept in sync (OpenAPI + contract tests).
- (−) One more artifact to publish (static files behind the same TLS proxy on the VPS).
