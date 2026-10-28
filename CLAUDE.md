# auto-msg-backend — Instruções para Claude

Backend da plataforma multi-tenant de atendimento no WhatsApp (Cloud API oficial) com motor
híbrido: fluxos determinísticos + LLM com ferramentas. Primeiro cliente: restaurante em Bonfim
Paulista-SP. Java 21, Spring Boot 4.1, Maven multi-módulo, PostgreSQL 17 + pgvector, Flyway.
Visão: [`README.md`](README.md); arquitetura: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md);
decisões técnicas: [`docs/adr/`](docs/adr/README.md); próximo trabalho:
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
- **Pedido só é confirmado por ação determinística** (clique no botão de confirmação, D-006),
  nunca por ferramenta chamada pelo LLM.
- **Webhook:** valida `X-Hub-Signature-256` sobre o corpo cru, grava e responde 200; nada de
  processamento síncrono no controller.
- **Idempotência:** todo consumidor de evento tolera duplicatas (chaves únicas `event_key`,
  `idempotency_key`, `confirmation_wamid`).
- **Janela de 24h:** fora dela só template aprovado; a janela conta a partir de mensagens do
  cliente (ecos do app não contam).
- **Custo:** toda chamada de LLM registra uso/custo por tenant; minimizar o número de mensagens
  enviadas (cada uma é cobrada).
- **LGPD:** não logar conteúdo de mensagem nem telefone completo em nível INFO; enviar ao LLM
  só o necessário.

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
- Filas (M2): `InboundWorker` → `InboundEventProcessor` (eco-bot, status de entrega) →
  `OutboundMessageRepository.enqueue` (chave de idempotência) → `SenderWorker` (lease, janela
  `CustomerServiceWindow`, retry de `WorkerProperties`) → `ChannelGateway`
  (`WhatsAppCloudApiGateway`). Nos ITs, `conversa.workers.enabled=false` e chame `runOnce()`;
  a Graph API é simulada por `FakeGraphApi` (nunca a real).
- Tenants (M3): `TenantService` (criar, configurar, config efetiva), `TenantChannelService`
  (números, resolução por `phone_number_id`), `TenantConfigResolver` (defaults do pack +
  config do tenant, validada por `config-schema.json`), `SegmentPackRegistry`,
  `TenantBootstrap` (`BOOTSTRAP_*`), `ChannelAccessTokens` (token por número).
- Fronteiras de módulos: `ArchitectureTest`.
- SPIs do núcleo: `SegmentPack`, `Tool`, `ToolInvocation`, `ToolResult` em `platform-core`.
- Segment Pack de restaurante: `RestaurantSegmentPack`; fiação em `PlatformConfiguration`.
