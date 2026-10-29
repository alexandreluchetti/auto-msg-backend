# auto-msg-backend — Instruções para Claude

Backend da plataforma multi-tenant de atendimento humanizado no WhatsApp (Cloud API oficial):
LLM com ferramentas e persona por empresa + regras determinísticas. Primeiro cliente: restaurante em Bonfim
Paulista-SP. Java 21, Spring Boot 4.1, Maven multi-módulo, PostgreSQL 17 + pgvector, Flyway.
Visão: [`README.md`](README.md); arquitetura: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md);
decisões técnicas: [`docs/adr/`](docs/adr/README.md) (direção atual: ADR-011, atendimento
humanizado); próximo trabalho (trilha H):
[`docs/ROADMAP.md`](docs/ROADMAP.md).

O painel web vive em repositório separado (`../auto-msg-frontend`, criado no M8): **não
adicione código de UI aqui**. O backend expõe API REST `/api/v1` + SSE com contrato OpenAPI
(ADR-009).

## Regras de trabalho

- **Só implemente o que estiver aprovado em [`docs/decisions.md`](docs/decisions.md)** (seção
  "Approved") e nos ADRs aceitos. Os itens de "Technical defaults" são escolhas técnicas já
  aceitas; qualquer decisão ausente ou na seção "Proposed" deve ser discutida antes.
- Trabalhe **um marco do roadmap por vez**. Ao fim de cada etapa, apresente o que foi feito, os
  arquivos principais e o resultado real de `./mvnw verify`, e aguarde aprovação antes de
  avançar.
- Nunca afirme que testes passaram sem executá-los.
- Commits somente quando o usuário pedir.
- **Fluxo de branches obrigatório** (detalhes em [`CONTRIBUTING.md`](CONTRIBUTING.md)):
  - `main` = produção (tag `vX.Y.Z` a cada release, versão do `pom.xml`); `dev` = integração.
  - **Nunca commitar direto em `dev` ou `main`.** Toda mudança nasce de uma branch
    `feat/`, `fix/`, `refactor/` ou `chore/<nome-em-kebab-case>` criada a partir da `dev`
    atualizada, vai para a `dev` por pull request e é apagada depois do merge.
  - A `main` só recebe pull request vindo da `dev` (check `source-branch-is-dev`).
  - Depois de todo merge na `main`, a `dev` é sincronizada automaticamente pelo workflow
    `sync-dev-with-main` (PR `main` → `dev` com merge commit). Confira que
    `git log origin/dev..origin/main` ficou vazio; se o workflow falhar, faça o PR à mão.
  - Antes de commitar, confira a branch atual (`git branch --show-current`); se estiver em
    `dev` ou `main`, crie a branch de trabalho primeiro.
- Idiomas:
  - Conversa com o usuário: **pt-BR**.
  - Código, nomes, comentários, logs, documentação técnica (README, ARCHITECTURE, ROADMAP,
    ADRs, decisions, CONTRIBUTING) e **mensagens de commit e de PR: inglês**.
  - Textos exibidos ao usuário final: **pt-BR** — mensagens do bot ao cliente no WhatsApp,
    templates, prompts dos Segment Packs e mensagens de erro da API destinadas à equipe.
  - Este arquivo (instruções para Claude) fica em pt-BR.
- Não criar recursos na nuvem, contratar serviços ou gerar custos sem aprovação explícita
  (Meta/WhatsApp, Anthropic, VPS, storage de backup). Testes automatizados nunca chamam as APIs
  reais do WhatsApp ou do Claude.
- Mudou uma decisão de arquitetura? Escreva um novo ADR em `docs/adr/` e atualize o índice. Nova
  regra de negócio aprovada? Registre em `docs/decisions.md` (próximo `D-0NN`).

## Arquitetura

Monólito modular (ADR-007), pacote base `br.com.conversa`:

| Módulo | Pacote | Responsabilidade |
|---|---|---|
| `platform-core` | `core` | Tipos de domínio e SPIs (`SegmentPack`, `Tool`, `LlmClient`...). **Java puro.** |
| `channel-whatsapp` | `channel.whatsapp` | Adaptador Cloud API: webhook, assinatura, parsing, envio, status. |
| `llm-anthropic` | `llm.anthropic` | Adaptador Claude da porta `LlmClient`. |
| `stt-openai` | `speech.openai` | Adaptador OpenAI da porta `SpeechToText` (transcrição de áudio, ADR-012). |
| `segment-restaurant` | `segment.restaurant` | Segment Pack de restaurante: ferramentas, fluxos, prompts, schema. |
| `platform-app` | `app` | Spring Boot: composição, JPA/Flyway, filas, workers, API REST/SSE. |

Regras (verificadas por `ArchitectureTest`):
- `platform-core` não depende de Spring, JPA nem de outros módulos.
- Segmentos, canais e adaptadores de LLM dependem **só** do `platform-core`; nunca uns dos outros.
- `platform-app` é o único lugar que liga módulos (beans em `app.config`).
- Nada específico de segmento no núcleo: se surgir `if (segmento == ...)`, a regra pertence a
  um Segment Pack ou à configuração do tenant. Precedência: plataforma < pack < tenant.

## Regras de domínio que não podem ser quebradas

- **Isolamento por tenant:** toda tabela de negócio tem `tenant_id` e política RLS
  (`tenant_isolation`, como em `V4`); todo acesso a ela passa por
  `TenantTransactions.inTenant/runInTenant`, que define `app.tenant_id` e troca para o role
  `conversa_app` (ADR-010). Fora disso a conexão é a do dono das tabelas e o RLS **não** protege.
  Teste novo de repositório deve provar que outro tenant não enxerga os dados (modelo:
  `TenantIsolationIT`). Busca na borda antes do tenant (por `phone_number_id`) só por função
  `SECURITY DEFINER`.
- **Segredos nunca no banco:** tokens ficam em variáveis de ambiente; o banco guarda só o nome
  (`tenant_channel.access_token_ref`).
- **Fatos de negócio só por ferramentas** (D-007). O LLM nunca é fonte de preço, horário, taxa
  ou disponibilidade.
- **Pedido só é confirmado por ação determinística** (toque no botão ou confirmação clara em
  texto logo após o resumo, reconhecida por regra fixa — D-006 revisada pela D-016),
  nunca por ferramenta chamada pelo LLM.
- **Webhook:** valida `X-Hub-Signature-256` sobre o corpo cru, grava e responde 200; nada de
  processamento síncrono no controller.
- **Idempotência:** todo consumidor de evento tolera duplicatas (chaves únicas `event_key`,
  `idempotency_key`, `confirmation_wamid`).
- **Janela de 24h:** fora dela só template aprovado; a janela conta a partir de mensagens do
  cliente (ecos do app não contam).
- **Custo:** toda chamada de LLM ou transcrição registra uso/custo por tenant; no máximo 2 mensagens
  por resposta (D-014), cada uma é cobrada.
- **LGPD:** não logar conteúdo de mensagem nem telefone completo em nível INFO; enviar ao LLM
  só o necessário.
- **Atendimento humanizado** (D-012, ADR-011): o bot nunca diz que é humano (perguntado, diz que
  é o assistente virtual da empresa e oferece chamar alguém); nada de "digite 1"; botões e listas
  só para escolhas reais, decididos pelo `ResponseComposer`; textos fixos seguem a persona.
- **Ritmo humano sem bloquear threads:** "digitando" e atrasos são agendados na fila de saída
  (`available_at`), nunca com `sleep`; mensagens pendentes de um turno são canceladas se o
  cliente escrever de novo.

## Stack

- Java 21, Spring Boot 4.1.x (`spring-boot-starter-webmvc`, actuator, validation, data-jpa,
  flyway), virtual threads habilitadas
- PostgreSQL 17 + pgvector (`pgvector/pgvector:pg17`), Flyway
- JPA/Hibernate (`ddl-auto: validate` — nunca gera DDL); `JdbcClient` para filas e consultas
  específicas
- Jackson 3 (`tools.jackson`)
- Testes: JUnit 5, AssertJ, ArchUnit, Testcontainers 2 (ITs com PostgreSQL real)
- LLM (a partir do M5): SDK oficial `com.anthropic:anthropic-java`

## Desenvolvimento local

```bash
docker compose up -d postgres                 # PostgreSQL + pgvector em localhost:5433
./mvnw -pl platform-app -am spring-boot:run   # API em localhost:8081
./mvnw verify                                 # build + unitários + ITs (requer Docker)
./mvnw test                                   # só unitários (sem Docker)
./mvnw -pl platform-app -am verify            # um módulo e suas dependências
docker compose --profile app up --build       # app em container
```

Variáveis de ambiente: tabela no [`README.md`](README.md); chaves em `.env.example`. Segredos
(app secret da Meta, chave da Anthropic, tokens de acesso) só por variável de ambiente, nunca
versionados.

## Convenções de código

- Java 21: `record` para DTOs/valores, `sealed` para hierarquias fechadas. Sem Lombok.
- Injeção por construtor; `@Configuration(proxyBeanMethods = false)`.
- Configuração via `@ConfigurationProperties` (records) com prefixo `conversa.*`.
- Migrações Flyway em `platform-app/src/main/resources/db/migration`, `V<n>__<descricao>.sql`.
  **Nunca altere uma migração já aplicada — nem comentários** (muda o checksum e quebra o
  `validate`). Por isso `V1` e `V2` mantêm os comentários originais em português.
- Javadoc/comentários em inglês, curtos, explicando o *porquê*.
- Logs estruturados: use MDC (`tenant_id`, `conversation_id`, `wamid`), nunca concatene PII.

## Testes

- Unitários: `*Test` (Surefire), sem Spring quando possível.
- Integração: `*IT` (Failsafe) com `TestcontainersConfiguration` (imagem
  `pgvector/pgvector:pg17`) e `@Testcontainers(disabledWithoutDocker = true)`.
- Payloads da Meta como fixtures em `src/test/resources/whatsapp/` de cada módulo, com números
  fictícios (WABA `100000000000001`, phone_number_id `200000000000001`, clientes
  `551699000000x`). Ao capturar payloads reais (número de teste), anonimize antes de versionar.
- Ao criar tabelas, limpe-as no `@BeforeEach` dos ITs que as usam.
- Cada marco do roadmap termina com seus critérios de aceite cobertos por teste.

## Armadilhas do ambiente

- PowerShell 5.1 quebra argumentos com aspas duplas em comandos nativos (ex.: `git commit -m`
  com aspas na mensagem): use `git commit -F <arquivo>` ou o Git Bash.
- `Get-Content`/`Set-Content` sem encoding explícito corrompem acentos; prefira as ferramentas
  de edição ou bash.
- As portas 5432 e 8080 costumam estar ocupadas por outros projetos locais (ex.:
  ranking-beach-tennis); por isso os padrões aqui são 5433 e 8081. Ao checar `/actuator/health`,
  confirme que a resposta veio desta app.
- Se o Docker Desktop não estiver rodando, os ITs são pulados (não falham): confira no
  relatório do Failsafe que eles rodaram antes de afirmar que passaram.
- Enquanto a app roda a partir de `platform-app/target/`, o Windows bloqueia o jar e
  `./mvnw verify` falha no repackage: pare-a antes.

## Referências rápidas

- Regras de negócio aprovadas: [`docs/decisions.md`](docs/decisions.md)
- Webhook (M1): `WhatsAppWebhookController` → `WebhookSignatureVerifier`,
  `WebhookPayloadParser` (chaves `msg:`, `status:`, `echo:`, `<field>:<sha256>`) →
  `InboundEventRepository` (`inbound_event`, `ON CONFLICT DO NOTHING`).
- Filas (M2): `InboundWorker` → `InboundEventProcessor` (mensagens do cliente, status de entrega) →
  `OutboundMessageRepository.enqueue` (chave de idempotência) → `SenderWorker` (lease, janela
  `CustomerServiceWindow`, retry de `WorkerProperties`) → `ChannelGateway`
  (`WhatsAppCloudApiGateway`). Nos ITs, `conversa.workers.enabled=false` e chame `runOnce()`;
  a Graph API é simulada por `FakeGraphApi` (nunca a real).
- Tenants (M3): `TenantService` (criar, configurar, config efetiva), `TenantChannelService`
  (números, resolução por `phone_number_id`), `TenantConfigResolver` (defaults do pack +
  config do tenant, validada por `config-schema.json`), `SegmentPackRegistry`,
  `TenantBootstrap` (`BOOTSTRAP_*`), `ChannelAccessTokens` (token por número).
- Dados do negócio (M4): porta `BusinessDirectory` (`platform-core`, cardápio/horários/bairros)
  → `JdbcBusinessDirectory`; `TenantSettings` (config efetiva); importação CSV em
  `BusinessDataImporter` (formato em [`docs/IMPORT.md`](docs/IMPORT.md), via
  `BOOTSTRAP_DATA_DIR`); ferramentas do restaurante em `segment.restaurant.tool`
  (`search_menu`, `get_business_hours`, `get_delivery_fee`, `get_business_info`), testadas com
  `FakeBusinessDirectory`.
- Respostas com LLM (M5): `InboundEventProcessor` → `ConversationRepository`
  (`contact`/`conversation`/`message`, debounce em `reply_due_at`) → `ReplyWorker` →
  `AssistantEngine` (prompt do pack, histórico, loop de ferramentas, `ToolInputValidator`) →
  porta `LlmClient` (`platform-core`) → `AnthropicLlmClient`; custo em `llm_call`
  (`LlmPricing`, `conversa.llm.prices`). Nos testes o modelo é `ScriptedLlm` (nunca a API real).
- Transbordo (M6): `HandoffService` (ecos → `HUMAN` com `paused_until`, `asksForPerson`,
  `handOver` com aviso ao cliente e alerta por template aos `staffNumbers`); ferramenta de
  controle `request_human` no `AssistantEngine`; `failure_streak` em `conversation`; retomada
  na primeira mensagem do cliente após a pausa. Teste ponta a ponta: `HandoffFlowIT`.
- Pedidos (M7): ferramentas de rascunho em `segment.restaurant.tool.CartTools` (carrinho em
  `ConversationState` → `conversation.context`, preços sempre de `CartPricing`); `start_checkout`
  devolve `CheckoutRequest` → `ReplyWorker` grava `checkout` e envia botões (`confirm:`/`edit:`);
  só o toque em `confirm:` chama `OrderService.confirm` (sem LLM), que cria `customer_order` uma
  vez. Teste ponta a ponta: `OrderFlowIT`.
- API do painel (M8): `app.api` — `SecurityConfiguration` (sessão em cookie HttpOnly + CSRF
  para SPA; `/webhooks/**` público), `StaffDirectory` (`staff_user`, função `staff_login`),
  `AuthController`, `OrdersController` → `OrderBoard` (status e aviso ao cliente),
  `ConversationsController` (transbordos, devolver ao bot), `EventsController` →
  `PanelEventStream` (SSE após commit). Contrato: [`docs/api/openapi.yaml`](docs/api/openapi.yaml)
  — mantenha-o em dia ao mudar a API. Testes: `PanelApiIT`, `PanelEventsIT`. Nos testes MockMvc,
  POSTs autenticados precisam de `.with(csrf())`.
- LGPD e operação (M9): `app.privacy` — `PrivacyService` (aviso na primeira resposta, pedido
  "apagar meus dados" com confirmação por botão, anonimização em duas fases: dados agora, filas
  depois do envio da confirmação em `purgeQueues`), `RetentionJob` (diário);
  `app.ops.OperationsMetrics` (métricas por tenant). Deploy: `deploy/` + [`docs/DEPLOY.md`](docs/DEPLOY.md)
  (Caddy do repositório do painel, backup diário, Prometheus/Grafana). Testes: `PrivacyFlowIT`,
  `BackupRestoreIT`. Respostas de teste comparadas por texto devem ignorar o aviso com
  `PrivacyNotices.strip`.
- Persona (H2): `app.persona` — `PersonaPrompt` (renderiza o modelo com a persona),
  `PersonaTexts` (textos fixos por tom e trava contra "sou humano"), `PromptTemplates` (modelos
  versionados; o `prompts/system.md` do pack é semeado como padrão publicado). Config com rascunho e
  publicada em `TenantService` (`saveDraft`, `publishDraft`, `effectiveDraftConfig`). Teste:
  `PersonaVersionsIT`.
- Composição e ritmo (H3): `app.compose` — `ResponseComposer` (até 2 balões, marcador `---`,
  `offer_options` vira botões ou lista), `HumanPace` (atrasos), `TurnSender` (turno na fila com
  `available_at`). Nos ITs que enviam respostas use `conversa.assistant.pace-scale=0`; o
  `HumanPaceIT` testa os atrasos reais. Agrupamento e cancelamento (H4): `TurnCancellation`, janela
  `humanization.groupingSeconds` (o `conversa.assistant.debounce` dos testes sobrepõe); `GroupingIT`.
- Memória (H5): `app.memory` — `CustomerMemory` (nome, últimos pedidos e endereço lidos do contato e
  dos pedidos, sem tabela de perfil) e `ConversationSummarizer` (`conversation_summary`, modelo
  `conversa.assistant.summary-model`); entram em `AssistantEngine.Memory`. Teste: `MemoryIT`.
- Mídia (H6): `MediaInbox` (antes de cada resposta transcreve áudios pendentes pela porta `SpeechToText` e baixa as
  imagens desde a última resposta por `ChannelGateway.download`; nada de mídia é guardado), `MediaProperties`
  (`conversa.media.*`), módulo `stt-openai` (`OPENAI_API_KEY`). Nos testes a transcrição é falsa (nunca a API real)
  e a `FakeGraphApi` serve mídia com `media(id, mime, bytes)`. Teste: `MediaIT`.
- Eval do M5: `AssistantEval` (`eval/restaurant-questions.json`) chama a **API real e gera
  custo**: só rode com aprovação explícita do usuário e `ANTHROPIC_API_KEY`
  (`./mvnw -pl platform-app -am test -Dtest=AssistantEval -Deval=true -Dsurefire.failIfNoSpecifiedTests=false`);
  relatório em `platform-app/target/eval-report.md`.
- Fronteiras de módulos: `ArchitectureTest`.
- SPIs do núcleo: `SegmentPack`, `Tool`, `ToolInvocation`, `ToolResult` em `platform-core`.
- Segment Pack de restaurante: `RestaurantSegmentPack`; fiação em `PlatformConfiguration`.
