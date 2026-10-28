# auto-msg-backend

> Provisional project name: **auto-msg** (code name: Conversa). Multi-tenant platform that
> automates WhatsApp customer service for businesses — restaurants, clinics, salons, shops —
> deployed mostly through configuration, not new code.

## Product vision

- **Official channel:** WhatsApp Business Platform (Meta Cloud API) with coexistence — the
  business keeps its number and the WhatsApp Business app.
- **Hybrid engine:** deterministic flows for what must be exact (checkout, confirmation) plus
  an LLM (Claude) with tool calling to understand free text and answer from the business's
  data — never inventing prices, opening hours or fees.
- **Segments as packs:** each segment (e.g. restaurant) brings tools, flows, prompts and a
  configuration schema; each business customizes tone of voice, opening hours, catalog and
  rules.
- **Human handoff** at any time, with the bot pausing in that conversation.
- **Data isolation per business**, LGPD compliance, message and LLM cost tracked per business.

**First client:** a restaurant in Bonfim Paulista-SP (Brazil) — questions (opening hours,
address, menu, prices, payment, delivery), end-to-end delivery/pickup orders and handoff.

Business decisions are recorded in the [decision log](docs/decisions.md); architecture
decisions in [ADRs](docs/adr/README.md). Branches, pull requests and releases follow
[CONTRIBUTING.md](CONTRIBUTING.md).

## Documentation

| Document | Content |
|---|---|
| [docs/decisions.md](docs/decisions.md) | Approved business decisions, technical defaults, proposals |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Components, message flow, data model, multi-tenancy |
| [docs/adr/](docs/adr/README.md) | Architecture decisions and trade-offs |
| [docs/ROADMAP.md](docs/ROADMAP.md) | MVP in testable milestones and later phases |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Branches, pull requests, releases |
| [CLAUDE.md](CLAUDE.md) | Rules for AI-assisted development sessions |

## Repositories

The project is split into two repositories inside `auto-msg/`:

| Repository | Content |
|---|---|
| `auto-msg-backend` (this one) | Platform, WhatsApp and LLM integrations, REST/SSE API |
| `auto-msg-frontend` | Staff web panel — created in M8 (see [ADR-009](docs/adr/ADR-009-separate-frontend-repository.md)) |

## Stack

Java 21 · Spring Boot 4.1 · Maven multi-module · PostgreSQL 17 + pgvector · Flyway ·
Testcontainers · ArchUnit · Docker Compose.

```text
platform-core/        domain kernel and SPIs (plain Java)
channel-whatsapp/     WhatsApp Cloud API adapter
llm-anthropic/        Claude adapter for the LlmClient port
segment-restaurant/   restaurant Segment Pack
platform-app/         Spring Boot application (wiring, persistence, queues, panel API)
```

## Local development

Requirements: JDK 21 and Docker. Maven comes with the wrapper (`mvnw`).

1. Start PostgreSQL:

   ```bash
   docker compose up -d postgres
   ```

2. (Optional) Copy `.env.example` to `.env` and fill in the secrets. Only the database is
   required today.

3. Run the application:

   ```bash
   ./mvnw -pl platform-app -am spring-boot:run
   ```

4. Check it:

   ```bash
   curl http://localhost:8081/actuator/health
   ```

   Expected answer: `{"status":"UP"}`.

**Without docker-compose:** run the test class `TestConversaApplication` from the IDE — it
starts PostgreSQL with Testcontainers.

**App in a container:** `docker compose --profile app up --build`.

| Variable | Description | Default |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | PostgreSQL connection | local docker-compose (`localhost:5433`) |
| `SERVER_PORT` | HTTP port | `8081` |
| `POSTGRES_PORT` | Host port of the compose PostgreSQL | `5433` |
| `WHATSAPP_VERIFY_TOKEN` | Token Meta sends when verifying the webhook URL | empty (verification refused) |
| `WHATSAPP_APP_SECRET` | Meta app secret, validates `X-Hub-Signature-256` | empty (every webhook POST refused) |
| `WHATSAPP_ACCESS_TOKEN` | Platform Cloud API token, used by numbers without their own token reference | empty (those numbers cannot send) |
| `WHATSAPP_GRAPH_API_URL` | Graph API base URL | `https://graph.facebook.com` |
| `WHATSAPP_GRAPH_API_VERSION` | Graph API version | `v24.0` |
| `WORKERS_ENABLED` | Runs the inbound and outbound queue workers | `true` |
| `ASSISTANT_ENABLED` | Bot answers customers (needs `ANTHROPIC_API_KEY`); off only records the messages | `true` |
| `ASSISTANT_EFFORT` | Default reasoning effort of the assistant (`low`, `medium`, `high`); tenants may override it | `low` |
| `BOOTSTRAP_TENANT_SLUG` / `BOOTSTRAP_TENANT_NAME` | Tenant created at startup (pilot restaurant; test number in development). Empty slug: no bootstrap | empty |
| `BOOTSTRAP_TENANT_SEGMENT` | Segment Pack of that tenant | `restaurant` |
| `BOOTSTRAP_TENANT_CONFIG` | Tenant settings as a JSON object, merged over the pack defaults and validated by its schema; replaces the stored settings on every start | empty (keep stored) |
| `BOOTSTRAP_PHONE_NUMBER_ID` / `BOOTSTRAP_WABA_ID` | Cloud API number of that tenant | empty |
| `BOOTSTRAP_ACCESS_TOKEN_REF` | Name of the variable holding that number's token | empty (platform token) |
| `BOOTSTRAP_DATA_DIR` | Folder with that tenant's spreadsheets (`menu.csv`, `options.csv`, `hours.csv`, `zones.csv`, see [`docs/IMPORT.md`](docs/IMPORT.md)); each file present is imported on every start | empty (no import) |
| `ANTHROPIC_API_KEY` | Claude API key; without it the bot does not answer | — |
| `ANTHROPIC_DEFAULT_MODEL` | Default Claude model | `claude-opus-5-5` |

## Tests

```bash
./mvnw verify
```

- `*Test` — unit tests (Surefire), no Docker needed.
- `*IT` — integration tests with Testcontainers (Failsafe); skipped when Docker is not
  available.
