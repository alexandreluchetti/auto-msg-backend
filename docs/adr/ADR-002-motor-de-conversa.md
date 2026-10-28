# ADR-002 — Motor de conversa híbrido: fluxos determinísticos + LLM com ferramentas

- **Status:** Aceito
- **Data:** 2026-10-28

## Contexto

Clientes escrevem de forma livre ("me vê duas calabresa e uma coca de 2l, entrega no centro"),
mas partes do atendimento precisam ser exatas: preço, total, endereço, forma de pagamento e
confirmação do pedido. Erro do bot ali custa dinheiro e confiança.

## Decisão

Pipeline em camadas, nesta ordem, a cada turno:

1. **Guards determinísticos** (LGPD, opt-out, aviso de privacidade, conversa em `HUMAN`,
   horário de funcionamento). Nunca passam pelo LLM.
2. **Flow runtime**: fluxos declarativos (YAML) como máquinas de estado com *slots*.
   Respostas estruturadas (id de botão/lista) avançam o fluxo sem LLM.
3. **LLM com tool calling** para linguagem livre:
   - Ferramentas de **leitura** (cardápio, horários, taxa de entrega, status do pedido) são a
     única fonte de fatos de negócio.
   - Ferramentas de **rascunho** (adicionar/remover item do carrinho, definir endereço)
     alteram só o estado da conversa.
   - Ferramentas de **controle** (`start_checkout`, `request_human`) entregam o controle a um
     fluxo determinístico ou ao humano.
   - **Nenhuma ferramenta confirma pedido.** A confirmação é um passo do fluxo de checkout:
     resumo gerado pelo código a partir do banco + botão "Confirmar". Só o clique cria o pedido.
4. **Renderização**: botões/listas construídos pelo código a partir dos resultados das
   ferramentas; o LLM escreve o texto ao redor.

Salvaguardas: limite de iterações de ferramenta por turno; validação de entrada de toda
ferramenta contra schema; *scope guard* (só assuntos da empresa); se o LLM falhar ou recusar,
resposta padrão + oferta de atendente; N mal-entendidos seguidos → transbordo.

Estado da conversa (`conversation.context` JSONB): fluxo ativo, slots preenchidos, carrinho,
contadores. Histórico enviado ao LLM limitado às últimas mensagens + resumo do estado.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| **Só fluxos/menus** (árvore de decisão) | Previsível, mas frustra quem escreve livre; muito trabalho de manutenção por tenant. |
| **Só LLM ("agente livre")** | Flexível, mas difícil garantir preço/total corretos e confirmação explícita; comportamento menos testável. |
| **NLU clássico (intents + entidades)** | Exige treinar por segmento; LLM com ferramentas cobre o mesmo com menos esforço. |

## Consequências

- (+) Partes críticas são testáveis com testes unitários comuns; o LLM fica onde agrega.
- (+) Fluxos e ferramentas são do Segment Pack → reuso entre tenants do mesmo segmento.
- (−) Duas formas de comportamento para manter coerentes (transições fluxo ↔ LLM).
- (−) Requer um conjunto de avaliação (*evals*) com conversas reais para evoluir prompts sem
  regressão — planejado no M5.
