# ADR-008 — Transbordo para humano pelo app WhatsApp Business (coexistência) com pausa por eco

- **Status:** Aceito
- **Data:** 2026-10-28

## Contexto

O cliente quer que a equipe atenda pelo próprio app WhatsApp Business quando o bot transferir.
Com coexistência, mensagens enviadas pelo app chegam até nós como `smb_message_echoes`.
O painel de conversas fica para a fase 2.

## Decisão

- Estados da conversa: `BOT`, `HUMAN` (com `paused_until`), `CLOSED`.
- **Entrada em `HUMAN`:**
  - cliente pede atendente (guard determinístico + ferramenta `request_human`);
  - N falhas de entendimento seguidas ou erro técnico;
  - **eco recebido** para aquele contato → a equipe já está respondendo; o bot pausa sem
    avisar o cliente.
- **Durante `HUMAN`:** mensagens do cliente são só gravadas; nenhuma resposta automática.
  Cada eco renova `paused_until` (padrão 60 min, configurável por tenant).
- **Saída:** expiração de `paused_until` ou botão "devolver ao bot" no painel de pedidos.
  Comandos digitados no app não servem, pois seriam enviados ao cliente.
- **Aviso à equipe:** card no painel + mensagem para os números da equipe (template utility).
  Grupos não são suportados pela API.
- Ecos são gravados em `message` (direção `OUT_HUMAN`) para manter o histórico completo.

## Alternativas consideradas

- **Painel de conversas próprio no MVP:** controle total do estado, mas mais escopo e a equipe
  teria de trocar de ferramenta. Fase 2.
- **Ferramentas de atendimento de terceiros (Chatwoot etc.):** boas, mas mais uma integração e
  mais uma tela para a equipe.

## Consequências

- (+) Zero mudança de hábito para a equipe.
- (−) Ponto cego: respostas por companion não suportado (WhatsApp para Windows) não geram eco;
  o bot pode responder por cima do humano. Mitigação: orientação ao restaurante e pausa longa
  após pedido de atendente.
- (−) Sem eco, não sabemos quando o humano "terminou"; dependemos de timeout.
