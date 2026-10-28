# ADR-008 — Human handoff through the WhatsApp Business app (coexistence), paused by echoes

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

The client wants staff to answer in the WhatsApp Business app itself when the bot hands over.
With coexistence, messages sent from the app reach us as `smb_message_echoes`. The
conversations panel is left for phase 2.

## Decision

- Conversation states: `BOT`, `HUMAN` (with `paused_until`), `CLOSED`.
- **Entering `HUMAN`:**
  - the customer asks for an attendant (deterministic guard + `request_human` tool);
  - N misunderstandings in a row or a technical error;
  - **an echo received** for that contact → staff are already answering; the bot pauses
    without telling the customer.
- **While `HUMAN`:** customer messages are only stored; no automatic answer. Every echo renews
  `paused_until` (default 60 min, configurable per tenant).
- **Leaving:** `paused_until` expires, or the "hand back to bot" button in the orders panel.
  Commands typed in the app do not work, since they would be sent to the customer.
- **Staff alert:** a card in the panel + a message to the staff numbers (utility template).
  Groups are not supported by the API.
- Echoes are stored in `message` (direction `OUT_HUMAN`) to keep the full history.

## Alternatives considered

- **Our own conversations panel in the MVP:** full control of the state, but more scope and
  staff would have to switch tools. Phase 2.
- **Third-party service tools (Chatwoot etc.):** good, but one more integration and one more
  screen for staff.

## Consequences

- (+) Zero change of habits for staff.
- (−) Blind spot: answers from an unsupported companion (WhatsApp for Windows) produce no echo;
  the bot may talk over the human. Mitigation: guidance to the restaurant and a long pause
  after an attendant request.
- (−) Without echoes we do not know when the human "finished"; we rely on a timeout.
