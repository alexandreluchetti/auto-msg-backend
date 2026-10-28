# ADR-003 — Multi-tenancy: schema compartilhado com `tenant_id` + Row-Level Security; segmentos como pacotes

- **Status:** Aceito
- **Data:** 2026-10-28

## Contexto

O produto deve atender várias empresas de vários segmentos, principalmente por configuração.
Dados de uma empresa nunca podem vazar para outra. No início haverá poucos tenants, todos
numa VPS.

## Decisão

### Isolamento de dados

- **Um banco, um schema, coluna `tenant_id`** em toda tabela de negócio.
- **PostgreSQL Row-Level Security** como defesa em profundidade:
  `USING (tenant_id = current_setting('app.tenant_id')::uuid)`.
- `TenantContext` é definido na borda (webhook → `phone_number_id`; painel → usuário logado;
  job → tenant do evento) e aplicado com `SET LOCAL app.tenant_id` no início de cada transação.
- Role da aplicação **sem** `BYPASSRLS`; Flyway roda com role próprio. Tabelas de fila
  (`inbound_event`, `outbound_message`) sem RLS, pois o claim é cross-tenant.
- Testes de integração garantem que uma consulta sem tenant definido retorna zero linhas.

### Segmentos e customização

- **Segment Pack** (módulo Maven, ex.: `segment-restaurant`) implementa a SPI `SegmentPack`
  do `platform-core` e fornece: ferramentas, fluxos (YAML), prompts, templates de mensagem,
  `config-schema.json` e defaults.
- **Tenant** = `segment_id` + `segment_version` + `config` JSONB validado contra o schema do
  pack (tom de voz, nome do assistente, ferramentas habilitadas, regras de negócio, textos).
- **Precedência:** plataforma < pack < tenant. Versão do pack fixada por tenant para permitir
  evolução sem quebrar quem está em produção.
- Entidades genéricas no núcleo (catálogo, horários, área de atendimento, FAQ, contatos);
  entidades específicas no pack (pedido de delivery, agendamento, reserva).

## Alternativas consideradas

| Alternativa | Prós | Contras |
|---|---|---|
| **Schema por tenant** | Isolamento forte, backup/restauração por tenant | Migrações N vezes, pool de conexões por schema, complexidade operacional cedo demais |
| **Banco por tenant** | Isolamento máximo | Custo e operação inviáveis para pequenos clientes |
| **Só `tenant_id` sem RLS** | Simples | Um `WHERE` esquecido vaza dados; sem rede de proteção |
| **Segmentos por código (if/else ou herança)** | Rápido no 1º cliente | Vira um produto sob medida por cliente; contraria o objetivo |

## Consequências

- (+) Uma migração para todos; onboarding de tenant = inserir linhas + configuração.
- (+) RLS protege contra bugs de consulta.
- (−) É preciso garantir o `SET LOCAL` em toda transação (interceptor/aspecto + teste).
- (−) Um tenant muito grande pode exigir mover para banco próprio no futuro (o modelo permite
  extrair por `tenant_id`).
