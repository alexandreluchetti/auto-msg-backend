# Decision Log

Business decisions approved by the product owner. Anything not listed here as **Approved**
must not be implemented without discussion. Architecture decisions and their trade-offs live in
[`docs/adr/`](adr/README.md); a decision here may point to the ADR that implements it.

## Approved

### D-001 — Product and first client
A multi-tenant WhatsApp customer-service platform for businesses, generic across segments and
set up per business mostly through configuration, not code. The first client is a mid-size
restaurant in Bonfim Paulista-SP. Its MVP scope: answer questions (opening hours, address,
menu, prices, payment methods, delivery area and fees), take delivery and pickup orders end to
end, and hand the conversation to a human at any time.

### D-002 — Official channel, current number (coexistence)
Only the official WhatsApp Business Platform (Cloud API). The restaurant keeps its current
number and keeps using the WhatsApp Business app on it, through coexistence (ADR-001). No
unofficial WhatsApp Web automation.

### D-003 — Human service in the WhatsApp Business app
Staff answer customers in the WhatsApp Business app itself. When staff reply to a customer
from the app, the bot stops answering that conversation for a while; it also hands over when
the customer asks for an attendant or the bot fails to understand several times in a row
(ADR-008).

### D-004 — How orders reach the staff
Every confirmed order shows up in a web panel with a sound alert **and** is sent as a WhatsApp
message to the staff numbers configured by the business. Groups are not used (not supported by
the API).

### D-005 — Payment in the MVP
Payment happens on delivery or pickup. The bot only records the payment method chosen by the
customer (and the change needed, for cash) among the methods the business accepts. No online
payment in the MVP.

### D-006 — Orders are confirmed only by the customer's explicit action
The bot shows an order summary built from the database (items, prices, fee, total, address,
payment) with a confirmation button. Only the customer's click on that button creates the
order; the LLM can never confirm one.

### D-007 — The bot never invents business facts
Prices, opening hours, fees, availability and policies come only from the business's own data.
When the data does not answer a question, the bot says so and offers an attendant. Subjects
unrelated to the business are politely refused (also required by Meta's terms, ADR-001).

### D-008 — Scale and hosting
Target volume of the first client: up to 50 orders per day. Everything runs on a single VPS
with Docker Compose.

### D-009 — Separate frontend
The staff panel lives in its own repository (`auto-msg-frontend`), talking to this backend
through a REST + SSE API (ADR-009).

### D-010 — Panel stack and access
The panel repository is public on GitHub and built with React + Vite + TypeScript. Staff sign in
with e-mail and password; the session lives in an HttpOnly cookie (no token in the browser).

### D-011 — Order status notifications
Every status change made in the panel notifies the customer on WhatsApp: being prepared, out for
delivery or ready for pickup, completed, and cancelled. Each notice is a paid message; outside the
24h window it needs an approved template.

## Technical defaults (easy to change)

- Handoff pause after a staff reply from the app: 60 minutes, renewed on every reply,
  configurable per business.
- Inbound debounce: the bot waits ~2 s of silence and answers a burst of customer messages
  together, to save paid messages and LLM calls.
- Privacy notice with a link to the policy on the first contact of each customer.
- Message retention: 180 days by default, configurable per business; orders are kept, with
  personal data anonymized after the period.
- LLM: Claude, model `claude-opus-5-5` with low effort by default, configurable per business;
  the choice will be revisited with the M5 eval set (ADR-005).
- Late notifications outside the 24h window use approved utility templates.
- The 24h window counts from the time the customer sent their latest message (Meta's
  timestamp), not from when we received it; free text outside it is never sent.
- Messages are marked as read only when the bot answers them, so staff keep the unread badge in
  the WhatsApp Business app for the rest.
- Sending retries: up to 5 attempts for temporary errors (rate limits, Meta or network outages),
  waiting 5 s and doubling, up to 10 minutes; other errors fail at once.
- Each number may have its own Cloud API token, referenced by name (property or environment
  variable) and never stored in the database; numbers without one use the platform token.
- Restaurant configuration (pack v1): business name (required), address and city; assistant name
  and tone (friendly, formal, casual); delivery and/or pickup with an optional minimum order;
  payment methods among cash, card on delivery and Pix paid on delivery/pickup (no bot-generated
  Pix, D-005); staff numbers that receive orders; handoff pause (5–1440 min). Pack defaults:
  assistant "Assistente", friendly; delivery and pickup; cash and card on delivery; 60 min pause.
- Menu, opening hours and delivery zones (neighborhood → fee) are tables, not configuration,
  loaded from spreadsheets (`docs/IMPORT.md`) until the panel edits them. Items marked unavailable
  stay visible to the bot as "out of stock today"; holidays/special dates are not supported yet.
  The tenant's time zone defaults to `America/Sao_Paulo`.
- A neighborhood typed with a typo is never charged directly: the bot offers the closest
  registered names and asks the customer to confirm.
- Until onboarding from the panel exists, the pilot tenant and its number are created at startup
  from environment settings (`BOOTSTRAP_*`).
- Assistant (M5): answers 2 s after the customer's last message, with the latest 30 messages as
  context and up to 4 tool rounds. Media it cannot read (audio, images) get a request to type. On a refusal it answers "Desculpe, não posso
  ajudar com isso..." and on failures "Desculpe, não consegui responder agora...". Refused requests
  are retried by the API on a fallback model where supported.
- Each business may choose the model (`claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5`)
  and effort (`low`, `medium`, `high`) of its assistant in its configuration (`llm`).
- The M5 eval uses 56 synthetic questions over a synthetic restaurant until real, anonymized
  questions from the pilot replace them.
- Handoff (M6): the bot hands over when the customer plainly asks for a person, when the assistant
  decides it (`request_human`: complaints, questions about an existing order,
  anything it cannot solve) or after 2 answers in a row it could not give. The customer gets
  "Certo! Vou chamar alguém da equipe para continuar o seu atendimento por aqui. É só aguardar um
  pouquinho." Staff alerts show the customer's WhatsApp name and only the last 4 digits of the
  number. A staff reply from the app pauses the bot with no alert.
- Staff alert template to submit to Meta (utility, pt_BR), name configured in
  `HANDOFF_STAFF_ALERT_TEMPLATE`, suggested body: "Novo atendimento para a equipe: {{1}} — {{2}}.
  Responda pelo WhatsApp Business." Until it is approved, alerts are not sent by WhatsApp.
- Orders (M7): the assistant fills the cart and asks only for what is missing; prices, fees and
  totals always come from the catalog and zones. The minimum order applies to the items subtotal
  of delivery orders (not to pickup, fee not included). Cash orders need the note the customer pays
  with or "no change needed". While opening hours are registered, orders are only closed when the
  restaurant is open. The customer confirms only by tapping "Confirmar pedido" on the latest
  summary; a summary is no longer valid once the cart changes; a second tap creates nothing.
  Orders are numbered per restaurant (#1, #2...) and start as `RECEIVED`.
- New-order template to submit to Meta (utility, pt_BR), name in `ORDERS_STAFF_ALERT_TEMPLATE`,
  suggested body: "Novo pedido {{1}} de {{2}} — {{3}}, total {{4}}. Veja no painel ou no WhatsApp
  Business." Until it is approved, staff see orders in the conversation and, from M8, the panel.
- Panel (M8): sessions last 12 hours; staff passwords have at least 10 characters; the pilot's
  first panel user is created from `BOOTSTRAP_STAFF_*`. Orders move RECEIVED → PREPARING →
  OUT_FOR_DELIVERY (delivery) or READY_FOR_PICKUP (pickup) → COMPLETED, and can be CANCELLED until
  completed; steps may be skipped forward, never back. The board shows open orders, or every order
  of the last 24 hours, with the customer's WhatsApp name and number.
- Status notice texts (inside the 24h window): "Seu pedido #N já está sendo preparado!", "...saiu
  para entrega!", "...está pronto para retirada!", "Pedido #N concluído. Obrigado pela
  preferência!", "Seu pedido #N foi cancelado. Se tiver alguma dúvida, é só responder por aqui."
  Outside the window they need one approved template per status (`ORDERS_TEMPLATE_*`).
- LGPD (M9): the privacy notice goes in the first answer to each customer ("Este é o atendimento
  automático do <business>. Usamos seus dados só para atender você e cuidar dos seus pedidos
  (política de privacidade: <link>). Para apagar seus dados a qualquer momento, envie 'apagar meus
  dados'."); the link is the tenant's `privacy.policyUrl`. Erasure needs a tap on "Sim, apagar";
  it deletes the history and removes personal data from orders, which stay for tax purposes. Each
  business sets `retention.messageDays` (30–3650, default 180).
- Backups: one at start and one a day, kept 14 days on the server; copying them to another
  provider (rclone) is recommended and needs that storage account. Restore drills go into a new
  database, never over the live one.
- Local development ports: PostgreSQL on `5433`, API on `8081` (5432/8080 are used by other
  local projects).

## Proposed (awaiting approval)

- Table reservations: decided after the pilot (simple request vs. with capacity).
- Pix generated by the bot and payment link (phase 2).
- Transcription of voice messages (phase 2).
- Marketing campaigns with opt-in (phase 2).
- Conversations panel and knowledge base/menu editor (phase 2).
- Rename the code base package `br.com.conversa` to match the final project name.
