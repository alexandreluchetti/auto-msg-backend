# ADR-001 — Integração com WhatsApp via Cloud API oficial, com coexistência

- **Status:** Aceito
- **Data:** 2026-10-28

## Contexto

Precisamos enviar e receber mensagens no WhatsApp para várias empresas. O primeiro cliente
quer manter o número que já usa no app WhatsApp Business e que a equipe continue atendendo
pelo app quando o bot transferir a conversa.

## Decisão

1. Usar exclusivamente a **WhatsApp Business Platform — Cloud API** (Graph API), hospedada
   pela Meta. Nada de bibliotecas que automatizam o WhatsApp Web.
2. Onboarding do número atual por **coexistência** (app WhatsApp Business + Cloud API no mesmo
   número), via **Embedded Signup**.
3. Assinar os webhooks `messages`, `smb_message_echoes`, `history`, `smb_app_state_sync` e
   `account_update`.
4. Webhook:
   - `GET` de verificação (`hub.mode`, `hub.verify_token`, `hub.challenge`).
   - `POST` validado por **HMAC-SHA256 do corpo cru** com o *app secret*
     (`X-Hub-Signature-256`), comparação em tempo constante; rejeição → 401 sem processar.
   - Responde 200 após gravar em `inbound_event` (ver ADR-004). A Meta reenvia em caso de
     falha, então duplicatas são esperadas e tratadas por `event_key` único.
5. Envio: texto, interativos (botões até 3, listas até 10 itens), templates, mídia por URL/ID,
   *mark as read* e indicador de digitação. Controle da **janela de 24h** por conversa
   (`window_expires_at` atualizado a cada mensagem *do cliente*); fora dela, só template.
6. Status (`sent`, `delivered`, `read`, `failed`) atualizam `message` e registram
   `pricing.category`/`billable` para custo por tenant.
7. Token de acesso por tenant (*business integration system user token* do Embedded Signup)
   guardado cifrado; `tenant_channel.access_token_ref` aponta para ele.

## Políticas da Meta verificadas (28/10/2026) e impacto

| Política | Impacto |
|---|---|
| **Termos de 15/01/2026:** proíbem provedores de IA de distribuir assistentes de propósito geral; IA para atendimento do negócio continua permitida. Há uma política de preço própria para "AI Providers" (vigente desde 16/02/2026). | O produto é atendimento da empresa, não um assistente genérico. *Scope guard* no prompt e recusa de temas fora do negócio. Posicionar a plataforma como solução de atendimento (Tech Provider), não como "AI Provider". Revisar os termos a cada trimestre. |
| **Cobrança por mensagem** (desde 01/07/2025); a partir de **01/10/2026** mensagens de serviço acima de **1.000/mês por número** são cobradas e *utility* dentro da janela deixou de ser grátis. Faturamento em BRL disponível desde 07/2026. | Minimizar nº de mensagens (respostas consolidadas, *debounce* de entrada). Registrar custo real por tenant a partir do webhook de status. Precificar o produto com margem sobre o custo de mensagem. Cadastrar forma de pagamento na conta. |
| **Janela de 24h** e **templates** aprovados fora dela. | Notificações tardias via template *utility* (ex.: `pedido_saiu_para_entrega`); templates versionados no pack do segmento. |
| **Coexistência:** 20 msg/s fixos; ecos via `smb_message_echoes`; mensagens do app não abrem janela; sem grupos, listas de transmissão (somente leitura), catálogo, mensagens de ausência/saudação, respostas rápidas e etiquetas no app; **WhatsApp para Windows e WearOS não suportados** como companion; sincronização de histórico (180 dias) só nas 24h após o onboarding. | Vazão suficiente. Ecos acionam transbordo (ADR-008). Saudação/ausência passam a ser responsabilidade do bot. Equipe notificada por painel + números individuais (sem grupo). Orientar o restaurante a não usar WhatsApp para Windows. Importar histórico logo após o onboarding. |
| **Coexistência exige** ser Tech Provider/Solution Partner com Embedded Signup e *session logging*. | Caminho crítico do MVP. Ver "Riscos". |

## Alternativas consideradas

- **Bibliotecas não oficiais (WhatsApp Web):** descartado — risco de banimento, viola termos.
- **Número novo dedicado (sem coexistência):** mais simples (sem ecos, sem limites do app),
  mas o restaurante perderia o número conhecido e o atendimento pelo app. Fica como plano B.
- **BSP (360dialog, Twilio, Gupshup etc.) em vez de Cloud API direto:** acelera onboarding e
  suporta coexistência, mas adiciona custo por número e uma dependência. Mantemos o adaptador
  `channel-whatsapp` isolado para trocar por um BSP se o cadastro de Tech Provider atrasar.

## Consequências

- (+) Conformidade com a Meta; mesmo número para o cliente final; equipe segue no app.
- (−) Dependência do processo de verificação/aprovação da Meta para ir ao ar.
- (−) Humanos atendendo pelo app só são "vistos" pelo bot por eco; companion não suportado =
  ponto cego.

## Riscos e ações

1. **Cadastro como Tech Provider** (verificação do negócio, app review, Embedded Signup) pode
   levar semanas → iniciar em paralelo ao M0; plano B: BSP com coexistência.
2. Conferir a tarifa BRL de mensagens *service*/*utility* no rate card antes de fechar preço.
3. Validar os detalhes de coexistência na documentação oficial no momento do onboarding
   (fontes de terceiros divergem em alguns pontos).

## Referências

- Pricing: https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing
- Coexistência: https://developers.facebook.com/documentation/business-messaging/whatsapp/embedded-signup/onboarding-business-app-users
- Termos de IA (cobertura): https://techcrunch.com/2025/10/18/whatssapp-changes-its-terms-to-bar-general-purpose-chatbots-from-its-platform/
