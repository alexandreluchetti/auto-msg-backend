# ADR-001 — WhatsApp integration through the official Cloud API, with coexistence

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

We need to send and receive WhatsApp messages for many businesses. The first client wants to
keep the number it already uses in the WhatsApp Business app, and wants staff to keep
answering in the app when the bot hands a conversation over.

## Decision

1. Use only the **WhatsApp Business Platform — Cloud API** (Graph API), hosted by Meta. No
   libraries that automate WhatsApp Web.
2. Onboard the current number through **coexistence** (WhatsApp Business app + Cloud API on the
   same number), via **Embedded Signup**.
3. Subscribe to the webhooks `messages`, `smb_message_echoes`, `history`, `smb_app_state_sync`
   and `account_update`.
4. Webhook:
   - Verification `GET` (`hub.mode`, `hub.verify_token`, `hub.challenge`).
   - `POST` validated by **HMAC-SHA256 of the raw body** with the app secret
     (`X-Hub-Signature-256`), constant-time comparison; rejection → 401 without processing.
   - Answers 200 after writing to `inbound_event` (see ADR-004). Meta redelivers on failure,
     so duplicates are expected and handled by a unique `event_key`.
5. Sending: text, interactive messages (up to 3 buttons, lists up to 10 items), templates,
   media by URL/ID, mark as read and typing indicator. The **24h window** is tracked per
   conversation (`window_expires_at` updated on every message *from the customer*); outside it,
   only templates.
6. Statuses (`sent`, `delivered`, `read`, `failed`) update `message` and record
   `pricing.category`/`billable` for per-tenant cost.
7. Access token per tenant (business integration system user token from Embedded Signup),
   stored encrypted; `tenant_channel.access_token_ref` points to it.

## Meta policies checked (2026-10-28) and impact

| Policy | Impact |
|---|---|
| **Terms of 2026-01-15:** AI providers may not distribute general-purpose assistants; AI for the business's own customer service is still allowed. There is a separate pricing policy for "AI Providers" (in force since 2026-02-16). | The product is the business's customer service, not a generic assistant. Scope guard in the prompt and refusal of off-topic subjects. Position the platform as a customer-service solution (Tech Provider), not as an "AI Provider". Review the terms every quarter. |
| **Per-message pricing** (since 2025-07-01); from **2026-10-01** service messages above **1,000/month per number** are charged, and utility messages inside the window are no longer free. Billing in BRL available since 2026-07. | Minimize the number of messages (consolidated answers, inbound debounce). Record real cost per tenant from the status webhook. Price the product with a margin over message cost. Register a payment method on the account. |
| **24h window** and approved **templates** outside it. | Late notifications through utility templates (e.g. `pedido_saiu_para_entrega`); templates versioned in the segment pack. |
| **Coexistence:** fixed 20 msg/s; echoes through `smb_message_echoes`; app messages do not open the window; no groups, broadcast lists (read-only), catalog, away/greeting messages, quick replies or labels in the app; **WhatsApp for Windows and WearOS are not supported** as companions; history sync (180 days) only within 24h after onboarding. | Throughput is enough. Echoes trigger handoff (ADR-008). Greeting/away messages become the bot's job. Staff notified through the panel + individual numbers (no group). Tell the restaurant not to use WhatsApp for Windows. Import history right after onboarding. |
| **Coexistence requires** being a Tech Provider/Solution Partner with Embedded Signup and session logging. | Critical path of the MVP. See "Risks". |

## Alternatives considered

- **Unofficial libraries (WhatsApp Web):** rejected — risk of bans, violates the terms.
- **New dedicated number (no coexistence):** simpler (no echoes, no app limits), but the
  restaurant would lose its known number and answering through the app. Kept as plan B.
- **BSP (360dialog, Twilio, Gupshup etc.) instead of the Cloud API directly:** faster
  onboarding and supports coexistence, but adds a per-number cost and a dependency. The
  `channel-whatsapp` adapter stays isolated so it can be swapped for a BSP if the Tech
  Provider registration is delayed.

## Consequences

- (+) Compliant with Meta; same number for the end customer; staff stay in the app.
- (−) Going live depends on Meta's verification/approval process.
- (−) Humans answering in the app are only "seen" by the bot through echoes; an unsupported
  companion is a blind spot.

## Risks and actions

1. **Tech Provider registration** (business verification, app review, Embedded Signup) may
   take weeks → start in parallel with M0; plan B: BSP with coexistence.
2. Check the BRL rate of service/utility messages in the rate card before setting prices.
3. Validate coexistence details in the official documentation at onboarding time (third-party
   sources disagree on some points).

## References

- Pricing: https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing
- Coexistence: https://developers.facebook.com/documentation/business-messaging/whatsapp/embedded-signup/onboarding-business-app-users
- AI terms (coverage): https://techcrunch.com/2025/10/18/whatssapp-changes-its-terms-to-bar-general-purpose-chatbots-from-its-platform/
