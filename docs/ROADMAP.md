# Roadmap

Cada marco é pequeno, entrega algo testável e termina com testes automatizados verdes.
"Pronto" = critério de aceite abaixo atendido + docs atualizadas.

## Trilha paralela — onboarding na Meta (começar já)

Caminho crítico para ir ao ar (ver ADR-001):

- [ ] Verificação do negócio no Meta Business (portfólio da plataforma).
- [ ] App na Meta + cadastro como **Tech Provider** + Embedded Signup com *session logging*.
- [ ] Número de teste da Cloud API para desenvolvimento (M1–M8 rodam nele).
- [ ] Decidir plano B (BSP com coexistência) se o cadastro travar.
- [ ] Contrato/DPA com o restaurante e política de privacidade publicada.
- [ ] Levantar com o restaurante: cardápio em planilha, bairros/taxas, horários, formas de
      pagamento, números da equipe, quem usa WhatsApp em qual dispositivo (Windows ≠ suportado).

## MVP — restaurante (Bonfim Paulista)

| Marco | Entrega | Critério de aceite |
|---|---|---|
| **M0 — Fundação** ✅ | Docs, ADRs, esqueleto multi-módulo, Docker Compose, health check, ArchUnit | `mvnw verify` verde; `/actuator/health` = UP com Postgres |
| **M1 — Webhook de entrada** | `GET` de verificação, `POST` com HMAC, `inbound_event` idempotente, parsing de mensagens/status/ecos | Testes com payloads reais (fixtures); duplicata não gera 2º evento; assinatura inválida → 401 |
| **M2 — Saída e janela 24h** | `outbound_message` (outbox), `SenderWorker` com retry, janela de 24h, *mark as read*; bot "eco" no número de teste | Mensagem enviada ao número de teste; reenvio não duplica; fora da janela exige template |
| **M3 — Tenants e segmentos** | `tenant`, `tenant_channel`, RLS + `TenantContext`, SPI `SegmentPack`, registro de packs, validação de `config` por JSON Schema; seed do restaurante | Teste prova que tenant A não lê dados de B; config inválida é rejeitada |
| **M4 — Catálogo e informações** | Tabelas de catálogo/horários/zonas, importação de planilha (CSV), ferramentas de leitura | Ferramentas retornam dados corretos do tenant (testes unitários) |
| **M5 — Respostas com LLM** | `LlmClient` + adaptador Claude, loop de ferramentas, prompts do pack, *scope guard*, registro de custo; **eval set** com ~50 perguntas reais | ≥ 95% das perguntas do eval respondidas corretamente; zero preço inventado; custo por conversa medido |
| **M6 — Transbordo** | Guard + `request_human`, ecos → `HUMAN`, pausa/timeout, aviso à equipe | Eco pausa o bot; timeout devolve; teste de ponta a ponta com fixtures |
| **M7 — Pedido ponta a ponta** | Carrinho via ferramentas, fluxo de checkout determinístico (entrega/retirada, endereço/bairro, pagamento, troco), confirmação por botão, persistência idempotente | Pedido criado só com clique; total confere com o banco; clique duplicado não duplica pedido |
| **M8 — Painel de pedidos** | Criar o repo `auto-msg-frontend`; API REST + SSE no backend; login da equipe, quadro de pedidos (SSE + alerta sonoro), mudança de status notifica cliente (serviço ou template), "devolver ao bot" | Pedido aparece em < 3 s; mudança de status chega ao cliente |
| **M9 — LGPD e operação** | Aviso de privacidade, "apagar meus dados", job de retenção, métricas de custo por tenant, backup, deploy na VPS com TLS | Pedido de exclusão anonimiza dados; backup restaurado em teste; dashboards básicos |
| **Piloto** | Onboarding por coexistência do número real; 1–2 semanas acompanhando conversas | Taxa de transbordo e erros dentro do combinado com o restaurante |

**Ponto de decisão após o piloto:** reservas de mesa (solicitação simples vs. com capacidade).
O motor já comporta um fluxo `reservation` no pack de restaurante.

## Fase 2 — produto para o primeiro segmento

- Reservas (conforme decisão acima).
- Pagamento: Pix gerado pelo bot e link de pagamento (PSP via webhook de confirmação).
- Áudio: transcrição de mensagens de voz (muito comum no Brasil).
- Painel de conversas (histórico, assumir/devolver) e editor da base de conhecimento/cardápio.
- RAG com pgvector para tenants com conhecimento textual grande.
- Campanhas com opt-in (templates de marketing) e métricas para o restaurante.
- Integrações com PDV/iFood se o cliente pedir.

## Fase 3 — multi-segmento e escala comercial

- Segundo Segment Pack (ex.: `segment-scheduling` para clínicas/salões: agendamento).
- Onboarding self-service via Embedded Signup; cadastro de tenant pelo painel.
- Billing da plataforma (plano + repasse de custo de mensagens e LLM).
- Avaliar extração de serviços ou broker (RabbitMQ/SQS) se métricas justificarem.
