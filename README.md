# Conversa

> Nome provisório do projeto: **auto-msg** (codinome do código: Conversa). Plataforma multi-tenant de atendimento automatizado no WhatsApp para
> empresas — restaurantes, clínicas, salões, lojas — implantada principalmente por
> configuração, não por código novo.

## Visão do produto

- **Canal oficial:** WhatsApp Business Platform (Cloud API da Meta), com coexistência — a
  empresa mantém o número e o app WhatsApp Business.
- **Motor híbrido:** fluxos determinísticos para o que precisa ser exato (checkout, confirmação)
  + LLM (Claude) com *tool calling* para entender linguagem livre e responder com base nos
  dados da empresa — sem inventar preço, horário ou taxa.
- **Segmentos como pacotes:** cada segmento (ex.: restaurante) traz ferramentas, fluxos,
  prompts e schema de configuração; cada empresa customiza tom de voz, horários, catálogo e
  regras.
- **Transbordo para humano** a qualquer momento, com o bot pausando naquela conversa.
- **Isolamento de dados por empresa**, LGPD, custo de mensagens e de LLM por empresa.

**Primeiro cliente:** restaurante em Bonfim Paulista-SP — dúvidas (horário, endereço, cardápio,
preços, pagamento, entrega), pedidos de delivery/retirada de ponta a ponta e transbordo.

## Documentação

| Documento | Conteúdo |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Componentes, fluxo da mensagem, modelo de dados, multi-tenancy |
| [docs/adr/](docs/adr/README.md) | Decisões de arquitetura e trade-offs |
| [docs/ROADMAP.md](docs/ROADMAP.md) | MVP em marcos testáveis e fases seguintes |
| [CLAUDE.md](CLAUDE.md) | Convenções e regras para sessões de desenvolvimento com IA |

## Repositórios

O projeto (nome provisório **auto-msg**) é dividido em dois repositórios dentro de `auto-msg/`:

| Repositório | Conteúdo |
|---|---|
| `auto-msg-backend` (este) | Plataforma, integrações WhatsApp e LLM, API REST/SSE |
| `auto-msg-frontend` | Painel web da equipe — criado no M8 (ver [ADR-009](docs/adr/ADR-009-frontend-repositorio-separado.md)) |

## Stack

Java 21 · Spring Boot 4.1 · Maven multi-módulo · PostgreSQL 17 + pgvector · Flyway ·
Testcontainers · ArchUnit · Docker Compose.

```text
platform-core/        kernel do domínio e SPIs (Java puro)
channel-whatsapp/     adaptador WhatsApp Cloud API
llm-anthropic/        adaptador Claude para a porta LlmClient
segment-restaurant/   Segment Pack de restaurante
platform-app/         aplicação Spring Boot (composição, persistência, filas, API do painel)
```

## Como rodar localmente

Pré-requisitos: JDK 21 e Docker. O Maven vem pelo wrapper (`mvnw`).

1. Suba o Postgres:

   ```bash
   docker compose up -d postgres
   ```

2. (Opcional) Copie `.env.example` para `.env` e preencha os segredos. Para o esqueleto atual
   nada é obrigatório além do banco.

3. Rode a aplicação:

   ```bash
   ./mvnw -pl platform-app -am spring-boot:run
   ```

4. Verifique:

   ```bash
   curl http://localhost:8081/actuator/health
   ```

   Resposta esperada: `{"status":"UP"}`.

**Sem docker-compose:** rode a classe de teste `TestConversaApplication` pelo IDE — ela sobe
um Postgres em Testcontainers automaticamente.

**App em container:** `docker compose --profile app up --build`.

## Testes

```bash
./mvnw verify
```

- `*Test` — testes unitários (Surefire), não precisam de Docker.
- `*IT` — testes de integração com Testcontainers (Failsafe); são pulados se o Docker não
  estiver disponível.
