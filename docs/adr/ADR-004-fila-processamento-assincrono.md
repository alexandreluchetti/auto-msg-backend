# ADR-004 — Fila e processamento assíncrono no PostgreSQL (`SKIP LOCKED`), sem broker no MVP

- **Status:** Aceito
- **Data:** 2026-10-28

## Contexto

O webhook da Meta precisa de resposta rápida e reenvia em caso de falha (duplicatas).
Processar uma mensagem envolve LLM (segundos) e envio pela Graph API. Volume previsto:
≤ 50 pedidos/dia, alguns milhares de mensagens/mês, picos no almoço e jantar. Deploy numa VPS.

## Decisão

- **Entrada:** `inbound_event` (payload cru, `event_key` único, `status`, `attempts`,
  `available_at`). O webhook faz `INSERT ... ON CONFLICT (event_key) DO NOTHING` e responde 200.
- **Saída:** `outbound_message` como *outbox* transacional: a resposta é gravada na mesma
  transação que atualiza o estado da conversa; um `SenderWorker` envia e grava o `wamid`.
- **Workers:** claim com `SELECT ... FOR UPDATE SKIP LOCKED LIMIT n`, virtual threads,
  `LISTEN/NOTIFY` para acordar sem esperar o próximo *poll*.
- **Ordenação por conversa:** `pg_try_advisory_xact_lock(hash(tenant_id, wa_id))`; se ocupado,
  o evento volta com `available_at` alguns ms à frente.
- **Retry:** backoff exponencial com jitter; após N tentativas → `FAILED` (dead letter
  consultável) e conversa marcada para humano.
- **Abstração:** interface `JobQueue` para permitir trocar a implementação.
- **Cache:** nenhum servidor de cache no MVP; Caffeine em memória para configuração de tenant
  e catálogo (invalidação por evento local — há um único processo).

## Alternativas consideradas

| Alternativa | Por que não agora |
|---|---|
| **RabbitMQ** | Bom encaixe (filas, DLQ), mas mais um serviço para operar e sem transação com o banco → precisaríamos de outbox de qualquer forma. |
| **Kafka / Redpanda** | Excesso para o volume; operação pesada numa VPS. |
| **Redis Streams** | Mais uma peça só para fila; persistência e DLQ menos naturais. |
| **SQS / Pub/Sub** | Amarra a um provedor de nuvem; o deploy é VPS. |
| **JobRunr / db-scheduler** | Viáveis, mas a necessidade (claim + ordenação por conversa + debounce) é específica; o código próprio é pequeno. Reavaliar se crescer. |
| **`@Async` em memória** | Perde mensagens em restart; sem retry durável. |

## Consequências

- (+) Uma peça a menos para operar; atomicidade entre estado e fila; reprocessamento simples.
- (+) Toda mensagem fica auditável no banco.
- (−) Postgres vira gargalo em volume alto (ordem de centenas de msg/s) — muito acima do
  previsto; a interface `JobQueue` deixa a troca localizada.
- (−) Limpeza periódica das tabelas de fila (particionamento ou job de expurgo).
