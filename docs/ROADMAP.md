# Roadmap

Each milestone is small, delivers something testable and ends with green automated tests.
"Done" = acceptance criteria below met + docs updated.

## Parallel track — Meta onboarding (start now)

Critical path to go live (see ADR-001):

- [ ] Business verification in Meta Business (platform portfolio).
- [ ] Meta app + **Tech Provider** registration + Embedded Signup with session logging.
- [ ] Cloud API test number for development (M1–M8 run on it).
- [ ] Decide plan B (BSP with coexistence) if the registration stalls.
- [ ] Contract/DPA with the restaurant and a published privacy policy.
- [ ] Gather from the restaurant: menu as a spreadsheet, neighborhoods/fees, opening hours,
      payment methods, staff numbers, who uses WhatsApp on which device (Windows is not
      supported).

## MVP — restaurant (Bonfim Paulista)

| Milestone | Delivery | Acceptance criteria |
|---|---|---|
| **M0 — Foundation** ✅ | Docs, ADRs, multi-module skeleton, Docker Compose, health check, ArchUnit | `mvnw verify` green; `/actuator/health` = UP with PostgreSQL |
| **M1 — Inbound webhook** ✅ | Verification `GET`, `POST` with HMAC, idempotent `inbound_event`, parsing of messages/statuses/echoes | Tests with real payloads (fixtures); a duplicate creates no second event; invalid signature → 401 |
| **M2 — Outbound and 24h window** 🟡 implemented; live check on the test number pending (the echo bot was replaced by the assistant in M5) | `outbound_message` (outbox), `SenderWorker` with retry, 24h window, mark as read | Message sent to the test number; resend does not duplicate; outside the window a template is required |
| **M3 — Tenants and segments** ✅ | `tenant`, `tenant_channel`, RLS + `TenantContext`, `SegmentPack` SPI, pack registry, `config` validation by JSON Schema; restaurant seed | Test proves tenant A cannot read B's data; invalid config is rejected |
| **M4 — Catalog and information** ✅ | Catalog/opening hours/zones tables, spreadsheet import (CSV), read tools | Tools return the tenant's correct data (unit tests) |
| **M5 — LLM answers** 🟡 implemented; eval run with the real API pending (needs an API key) | `LlmClient` + Claude adapter, tool loop, pack prompts, scope guard, cost recording; **eval set** with ~50 real questions | ≥ 95% of eval questions answered correctly; zero invented prices; cost per conversation measured |
| **M6 — Handoff** ✅ | Guard + `request_human`, echoes → `HUMAN`, pause/timeout, staff alert | An echo pauses the bot; timeout hands back; end-to-end test with fixtures |
| **M7 — End-to-end order** ✅ | Cart through tools, deterministic checkout flow (delivery/pickup, address/neighborhood, payment, change), confirmation by button, idempotent persistence | Order created only by a click; total matches the database; a duplicate click creates no second order |
| **M8 — Orders panel** ✅ (panel: [`auto-msg-frontend`](https://github.com/alexandreluchetti/auto-msg-frontend)) | Create the `auto-msg-frontend` repo; REST + SSE API in the backend; staff login, orders board (SSE + sound alert), status change notifies the customer (service or template), "hand back to bot" | Order shows up in < 3 s; status change reaches the customer |
| **M9 — LGPD and operations** 🟡 implemented; deploy on the VPS pending (needs the server, see `docs/DEPLOY.md`) | Privacy notice, "delete my data", retention job, cost metrics per tenant, backup, deploy on the VPS with TLS | Deletion request anonymizes data; backup restored in a test; basic dashboards |
| **Pilot** | Coexistence onboarding of the real number; 1–2 weeks following conversations | Handoff and error rates within what was agreed with the restaurant |

## Track H — humanized service (before the pilot; D-012..D-017, ADR-011, ADR-012)

One small step at a time, each with its tests and its own commit and pull request.

| Step | Delivery | Acceptance criteria |
|---|---|---|
| **H1 — Direction** ✅ | ADR-011/012, decisions D-012..D-017, docs of both repositories | Plan approved; docs consistent |
| **H2 — Persona** ✅ | `persona` block in the restaurant schema; versioned `prompt_template` (segment default + tenant override, draft/published); `tenant_config_version`; persona-aware fixed texts; "never claim to be human" rule | Prompt renders the tenant persona; a published version changes the prompt; tests of the rule |
| **H3 — Composition and pace** ✅ | `ResponseComposer` (≤ 2 bubbles, list/buttons for real choices); turns in the outbox: typing indicator + bubbles with delays by length | Bubble split and delays deterministic in tests; no thread sleeps; typing payload as in the Cloud API docs |
| **H4 — Grouping and interruptions** ✅ | Per-tenant silence window; cancel pending bubbles of a turn when the customer writes again | A message during "digitando" yields one rebuilt answer, no stale bubble sent |
| **H5 — Memory** ✅ | `customer_profile` (name, last address, latest orders) offered by the assistant; `conversation_summary` for long conversations; retention and erasure cover both | "Mesmo endereço?" offered from the profile; erasure removes the profile |
| **H6 — Media** ✅ | Images to Claude; audio via `SpeechToText` (`stt-openai`); cost per tenant; fallbacks | Audio fixture transcribed by a fake STT and answered; failure asks to write |
| **H7 — Natural handoff** | Persona-aware handoff text; pause until staff hand back or 4 h without a staff reply; takeover from the panel; confirmation by clear text (D-016) | Bot silent until hand-back; safety net returns it; "pode mandar" after the summary creates the order once |
| **H8 — Settings API and simulator** | `/settings/*` (draft, publish, versions), `/simulator/*` (SSE, no WhatsApp), `/conversations` list and detail; OpenAPI | Simulator answers with the draft persona and real tools; published version used by WhatsApp |
| **H9 — Panel** | Persona with preview, human-pace settings, simulator, conversations (bot/staff distinction, take over / hand back, order in progress); admin role | Component tests per screen; build green |
| **H10 — Evaluation** | Multi-turn eval conversations with an LLM-judge rubric for naturalness plus the accuracy checks | Runs on demand against the real API (with approval); report per criterion |

**Decision point after the pilot:** table reservations (simple request vs. with capacity). The
restaurant pack would gain reservation tools (no YAML flows since ADR-011).

## Phase 2 — product for the first segment

- Reservations (per the decision above).
- Payment: Pix generated by the bot and payment link (PSP with a confirmation webhook).
- Knowledge base/menu editor in the panel (conversations panel moved to track H).
- RAG with pgvector for tenants with large textual knowledge.
- Opt-in campaigns (marketing templates) and metrics for the restaurant.
- POS/iFood integrations if the client asks.

## Phase 3 — multi-segment and commercial scale

- Second Segment Pack (e.g. `segment-scheduling` for clinics/salons: appointments).
- Self-service onboarding through Embedded Signup; tenant sign-up from the panel.
- Platform billing (plan + pass-through of message and LLM costs).
- Consider extracting services or a broker (RabbitMQ/SQS) if metrics justify it.
