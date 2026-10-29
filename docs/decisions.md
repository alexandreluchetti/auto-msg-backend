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
(ADR-008). *Revised by D-015: the pause lasts until staff hand the conversation back.*

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
order; the LLM can never confirm one. *Revised by D-016: a clear confirmation in text right after the
summary also counts.*

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

### D-012 — Humanized service
Talking to the business on WhatsApp must feel like talking to a person of its staff: natural,
informal, short messages, emojis in moderation, free conversation (buttons and lists only when
they really help, never "digite 1"), order data gathered along the way in any order, memory of
the customer, and the unexpected (audio, photos, complaints, jokes, changes of mind) handled as a
person would. The bot never claims to be human: asked directly, it says it is the business's
virtual assistant and offers to call someone. It never invents a fact (D-007). See ADR-011.

### D-013 — Persona, settings and simulator in the panel
Each business sets its assistant's persona (name, tone, formality, emojis, typical expressions,
greeting and farewell, sample replies) and the human-pace settings in the panel, tries them in a
WhatsApp-like simulator and then publishes them. The panel also shows conversations (bot, staff
and customer messages, the order in progress) and lets staff take over and hand back. This moves
the "conversations panel" out of phase 2.

### D-014 — Up to 2 messages per answer
An answer may be split into at most 2 WhatsApp messages (configurable 1–2 per business), only
where it reads naturally, since every message is billed.

### D-015 — The bot returns when staff hand back
After a handoff, the bot stays paused in that conversation until staff hand it back in the panel,
or after 4 hours without any staff reply (safety net). Staff can also take a conversation over.
Replaces the 60-minute pause of D-003.

### D-016 — Order confirmation by button or clear text
The summary is still built by code from the database. The customer confirms by tapping
"Confirmar pedido" or by a clear confirmation in text right after the latest summary ("pode
mandar", "confirmo", "fechado"...), recognized by a fixed rule, never by the LLM. Anything
ambiguous makes the bot ask again. Revises D-006.

### D-017 — Voice messages are transcribed
Voice messages are transcribed (OpenAI API, ADR-012) and answered like text; the audio is not
kept. Images are read by the assistant. This moves voice transcription out of phase 2.

## Technical defaults (easy to change)

- Handoff safety net (D-015): the bot returns after 4 hours without a staff reply, renewed on
  every reply, configurable per business (`handoff.pauseMinutes`, 5–1440 min; was 60).
- Inbound grouping (ADR-011): the bot waits 4 s of silence (2–15 s per business) and answers a burst of customer messages
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
- Human pace (ADR-011): typing indicator as soon as the answer is being prepared; each message
  waits 40 ms per character, at least 1.2 s and at most 6 s (per business). A customer message
  that arrives before the pending messages of an answer go out cancels them; the answer is
  rebuilt.
- Persona and human-pace settings are edited by panel users with the admin role; staff see
  conversations and orders and take over or hand back. The base prompt template is versioned by
  the platform, not edited in the panel. The simulator uses the real LLM (its cost is recorded for
  the business) and never sends WhatsApp messages.
- Customer memory: preferred name, last address and neighborhood and the latest 3 orders, kept
  under retention, erased on request, and mentioned in the privacy notice.
- Messages outside opening hours use the registered opening hours, with a configurable message;
  there is no separate "service hours".
- Local development ports: PostgreSQL on `5433`, API on `8081` (5432/8080 are used by other
  local projects).

## Proposed (awaiting approval)

- Table reservations: decided after the pilot (simple request vs. with capacity).
- Pix generated by the bot and payment link (phase 2).
- Marketing campaigns with opt-in (phase 2).
- Knowledge base/menu editor in the panel (phase 2; the conversations panel moved to D-013).
- Rename the code base package `br.com.conversa` to match the final project name.
