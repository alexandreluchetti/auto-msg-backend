# ADR-005 — Provedor de LLM atrás de uma porta própria; começar com a API do Claude

- **Status:** Aceito
- **Data:** 2026-10-28

## Contexto

O motor de conversa precisa de um LLM com tool calling confiável, bom português e custo
previsível por tenant. Queremos poder trocar de provedor ou de modelo sem mexer no motor.

## Decisão

- **Porta `LlmClient` em `platform-core`** com tipos neutros: mensagens, definições de
  ferramenta (JSON Schema), blocos de resposta (texto / chamada de ferramenta), `stopReason`,
  `usage` (tokens de entrada, saída, cache lido/escrito). O loop de ferramentas fica no motor,
  não no adaptador — o motor controla limites, validação e logging de cada chamada.
- **Adaptador `llm-anthropic`** com o SDK oficial `com.anthropic:anthropic-java`.
- **Modelo configurável por tenant** (`llm.model` em `tenant.config`). Padrão inicial:
  `claude-opus-5-5` com `effort` baixo para turnos de chat. Trocar para `claude-sonnet-5-5` ou
  `claude-haiku-5-5` é decisão de custo/qualidade a ser tomada com o *eval set* do M5,
  medindo custo por conversa concluída, não por requisição.
- **Prompt caching:** prefixo estável e determinístico (ferramentas → prompt do pack →
  configuração do tenant/FAQ), estado volátil (carrinho, hora atual) no fim. Verificar
  `cache_read_input_tokens` nas métricas.
- **Particularidades da API atual a respeitar no adaptador:** não usar `tool_choice` forçado
  (`any`/`tool`) — usar `auto` + ferramentas `strict`; *thinking* não é desligável nos modelos
  atuais (controle por `effort`); não enviar `temperature`/`top_p`; tratar
  `stop_reason = refusal` e habilitar *fallbacks* do lado do servidor.
- **Custo:** cada chamada grava `llm_call` (tenant, conversa, modelo, tokens, custo calculado
  por tabela de preços versionada em configuração).
- **Privacidade:** enviar ao LLM só o necessário (sem telefone completo); usar conta de API
  com termos comerciais (dados não usados para treino) e listar a Anthropic como suboperadora.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| **Spring AI como abstração** | Útil, mas a abstração comum tende a atrasar recursos específicos (caching, effort, fallbacks, refusal). Nossa porta é pequena; o adaptador pode até usar Spring AI no futuro se compensar. |
| **LangChain4j** | Mesmo argumento; traz mais do que precisamos (memória, agentes) e esconde o loop. |
| **Chamar a API HTTP diretamente** | O SDK oficial já cuida de retries, tipos e streaming. |
| **Modelo self-hosted** | Qualidade de tool calling em PT-BR e custo de operação de GPU não compensam neste volume. |

## Consequências

- (+) Troca de provedor/modelo isolada no adaptador e na configuração.
- (+) Custo de LLM visível por tenant desde o primeiro dia.
- (−) Recursos avançados de um provedor precisam ser expostos na porta conscientemente.
