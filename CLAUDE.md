# CLAUDE.md

Guia para sessões de desenvolvimento neste repositório. Leia antes de mudar código.

## O que é

Plataforma multi-tenant de atendimento no WhatsApp (Cloud API oficial) com motor híbrido
(fluxos + LLM com ferramentas). Visão e decisões: `README.md`, `docs/ARCHITECTURE.md`,
`docs/adr/`. Próximo trabalho: `docs/ROADMAP.md` — trabalhe um marco por vez.

## Repositórios

Este é o `auto-msg-backend`. O painel web vive em `auto-msg-frontend` (repositório irmão em
`../auto-msg-frontend`, criado no M8). Não adicione código de UI aqui; o backend expõe API
REST `/api/v1` + SSE com contrato OpenAPI (ADR-009).

## Comandos

```bash
./mvnw verify                                  # build completo + testes unitários e de integração
./mvnw test                                    # só unitários (sem Docker)
./mvnw -pl platform-app -am verify             # um módulo e suas dependências
./mvnw -pl platform-app -am spring-boot:run    # roda a app (precisa do Postgres)
docker compose up -d postgres                  # Postgres 17 + pgvector local
```

No Windows use `mvnw.cmd` no lugar de `./mvnw` fora do Git Bash.

## Estrutura

```text
platform-core/        tipos de domínio e SPIs (SegmentPack, Tool, LlmClient...) — Java puro
channel-whatsapp/     adaptador Cloud API (webhook, assinatura, envio, status)
llm-anthropic/        adaptador Claude da porta LlmClient
segment-restaurant/   Segment Pack de restaurante (ferramentas, fluxos, prompts, schema)
platform-app/         Spring Boot: composição, JPA/Flyway, filas, workers, API REST/SSE do painel
docs/                 ARCHITECTURE.md, ROADMAP.md, adr/
```

Pacote base `br.com.conversa.<módulo>` (`core`, `channel.whatsapp`, `llm.anthropic`,
`segment.restaurant`, `app`).

## Regras de arquitetura (verificadas por `ArchitectureTest`)

- `platform-core` não depende de Spring, JPA nem de outros módulos.
- Segmentos, canais e adaptadores de LLM dependem **só** do `platform-core`; nunca uns dos outros.
- `platform-app` é o único lugar que liga módulos (beans em `app.config`).
- Nada específico de segmento no núcleo: se surgir `if (segmento == ...)`, a regra pertence a
  um Segment Pack ou à configuração do tenant.
- Mudou uma decisão de arquitetura? Escreva um novo ADR em `docs/adr/` e atualize o índice.

## Regras de domínio que não podem ser quebradas

- **Isolamento por tenant:** toda tabela de negócio tem `tenant_id` e política RLS; toda
  transação de negócio roda com `app.tenant_id` definido. Teste novo de repositório deve
  provar que outro tenant não enxerga os dados.
- **Fatos de negócio só por ferramentas.** O LLM nunca é fonte de preço, horário, taxa ou
  disponibilidade.
- **Pedido só é confirmado por ação determinística** (clique no botão de confirmação), nunca
  por ferramenta chamada pelo LLM.
- **Webhook:** valida `X-Hub-Signature-256` sobre o corpo cru, grava e responde 200; nada de
  processamento síncrono no controller.
- **Idempotência:** todo consumidor de evento tolera duplicatas (chaves únicas
  `event_key`, `idempotency_key`, `confirmation_wamid`).
- **Janela de 24h:** fora dela só template aprovado; a janela conta a partir de mensagens do
  cliente (ecos do app não contam).
- **Custo:** toda chamada de LLM registra uso/custo por tenant; minimizar número de mensagens
  enviadas (cada uma é cobrada).
- **LGPD:** não logar conteúdo de mensagem nem telefone completo em nível INFO; enviar ao
  LLM só o necessário.

## Convenções de código

- Java 21: `record` para DTOs/valores, `sealed` para hierarquias fechadas, virtual threads
  habilitadas. Evite Lombok.
- Injeção por construtor; `@Configuration(proxyBeanMethods = false)`.
- Configuração via `@ConfigurationProperties` (records) com prefixo `conversa.*`; segredos só
  por variável de ambiente (`.env.example` documenta as chaves).
- Migrações Flyway em `platform-app/src/main/resources/db/migration`, `V<n>__<descricao>.sql`,
  nunca editar uma migração já aplicada. JPA com `ddl-auto: validate`.
- Javadoc/comentários em português, curtos, explicando o *porquê*. Nomes de código em inglês.
- Logs estruturados: use MDC (`tenant_id`, `conversation_id`, `wamid`), nunca concatene PII.

## Testes

- Unitários: `*Test` (JUnit 5 + AssertJ), sem Spring quando possível.
- Integração: `*IT` com Testcontainers (`TestcontainersConfiguration`, imagem
  `pgvector/pgvector:pg17`), rodam no `verify`.
- Payloads reais da Meta como fixtures em `src/test/resources/whatsapp/` (anonimizados).
- Cada marco do roadmap termina com seus critérios de aceite cobertos por teste.
- Nunca chame a API real do WhatsApp ou do Claude em testes automatizados; use stubs da porta.

## Fluxo de trabalho para sessões

1. Leia o marco atual em `docs/ROADMAP.md` e os ADRs relacionados.
2. Proponha o plano antes de mudanças grandes; mudanças pequenas e focadas.
3. Rode `./mvnw verify` antes de considerar algo pronto e informe o resultado real.
4. Atualize docs (ARCHITECTURE/ROADMAP/ADR) na mesma mudança quando o comportamento mudar.
5. Ao usar a API do Claude, confira IDs de modelo e parâmetros na documentação atual (a API
   muda: ex. `tool_choice` forçado e `temperature` não são aceitos nos modelos atuais).
