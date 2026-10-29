# ADR-011 — Humanized service: persona, composition, human pace and customer memory

- **Status:** Accepted
- **Date:** 2026-10-29
- **Refines:** ADR-002 (conversation engine), ADR-008 (handoff); decisions D-012 to D-015

## Context

The product goal changed (D-012): talking to the business on WhatsApp must feel like talking to
a person of its staff, not to a menu bot. Short informal messages, free conversation, order data
gathered along the way in any order, memory of the customer, and the unexpected (audio, photos,
complaints, jokes, changes of mind) handled as a person would. Two limits stay: the bot never
claims to be human, and it never invents a business fact (D-007).

What the platform already does well and keeps: tools as the only source of facts, the cart that
asks only for what is `missing`, handoff by echoes, idempotent queues, LGPD. What it lacks: a
persona richer than a name and three tones, a way to deliver an answer other than one message
sent at once, memory beyond the latest 30 messages, media, and a place for the business to tune
and try all this.

Facts checked on 2026-10-29:

- Meta's WhatsApp Business Messaging Policy allows automation inside the 24h window and requires
  "prompt, clear, and direct escalation paths" to a human; it has no rule to label automation,
  and forbids misleading customers about the nature of the business. The ban of 2026-01-15 on
  general-purpose AI assistants does not cover a business's own customer service (ADR-001).
- The Cloud API has a typing indicator: `POST /messages` with `status: read`, the received
  `message_id` and `typing_indicator: {type: text}`; it marks the message read and lasts until
  the reply or 25 s. Meta asks to show it only when a reply follows.
- Every message is billed above 1,000 service messages a month per number, so splitting an
  answer into bubbles costs money.

## Decision

1. **Persona per business** (D-013): a `persona` block in the tenant configuration (name, tone,
   formality 1–5, emoji use, typical expressions, greeting, farewell, sample replies), rendered
   into a **versioned prompt template** (`prompt_template`, per segment with optional per-tenant
   override, draft/published) instead of a file compiled into the pack. Businesses edit the
   persona, not the template. Configuration itself gets **draft and published versions**
   (`tenant_config_version`); the simulator runs the draft, publishing makes it live.
2. **Generation and composition are separate.** The engine produces the answer (text plus,
   optionally, a choice the customer should make). A `ResponseComposer` decides delivery: at most
   `humanization.maxBubbles` bubbles (default and maximum 2, D-014), split where it reads
   naturally, and an interactive list or buttons only when the answer offers a small set of
   options (e.g. sizes of an item). Never "digite 1".
3. **Human pace without blocking threads.** A reply is a *turn*: a typing indicator right away,
   then each bubble scheduled in the outbox (`available_at`) with a delay proportional to its
   length, between a minimum and a maximum, refreshing the indicator before each one. Workers keep
   polling; nothing sleeps.
4. **Grouping.** The reply waits for a per-tenant silence window (default 4 s). A customer message
   that arrives while bubbles of the current turn are still pending cancels them and the answer is
   rebuilt with everything the customer said.
5. **State and memory.** The order in progress stays structured state (the cart's slots).
   Long conversations get a rolling summary (`conversation_summary`) so the model sees summary +
   latest messages. A **customer profile** across conversations (preferred name, last address and
   neighborhood, latest orders) lets the bot offer "mando no mesmo endereço?". It is personal data:
   announced in the privacy notice, kept under retention, erased on request.
6. **Media.** Images go to Claude, which reads them (menu photo, payment receipt). Audio is
   transcribed by a separate provider (ADR-012) and enters the conversation as text. Media that
   cannot be understood gets a natural request to write, or a handoff.
7. **Handoff as a person would** ("Vou chamar alguém aqui pra te ajudar, só um minutinho"). The bot
   stays paused until staff hand the conversation back in the panel, or after 4 hours without any
   staff reply (D-015); staff can also take a conversation over from the panel.
8. **Never claiming to be human.** The prompt has a fixed rule: asked directly, the bot says
   naturally that it is the business's virtual assistant and offers to call someone. The privacy
   notice of the first contact keeps saying "atendimento automático".
9. **Order confirmation** (D-006 revised): the summary stays built by code from the database; the
   customer confirms by tapping its button **or** by a clear confirmation in text ("pode mandar",
   "confirmo"...) right after the latest summary, recognized by a deterministic rule, never by the
   LLM. Anything ambiguous makes the bot ask again.
10. **Fixed texts follow the persona.** Refusal, failure, handoff and confirmation texts stay
    deterministic but are written per tone; the LLM never writes them.
11. **Evaluation.** Besides accuracy, multi-turn conversations are scored for naturalness by an
    LLM judge with a rubric; it runs against the real API only on purpose (it costs money).

## Alternatives considered

- **Let the LLM produce several messages directly** (one tool call per bubble): unpredictable
  count and cost; composition in code keeps the limit and the pacing deterministic.
- **Sleep before sending** (Thread.sleep or a delayed executor holding the turn): simple, but holds
  workers and loses pending bubbles on restart; the outbox already schedules and survives restarts.
- **Fine-tuned "human" model or long few-shot prompts:** costly and hard to change per business;
  a persona block plus a few sample replies is enough for tone, and evals catch regressions.
- **Flows in YAML (ADR-002 step 3):** dropped from the plan; the cart's `missing` list already lets
  the model ask only for what is needed, in any order, without a form.

## Consequences

- (+) Conversations sound like the business's own staff, tunable by the business without a deploy.
- (+) Pace and bubbles are deterministic and testable with a scripted LLM.
- (−) More messages per answer (up to 2x) and a few seconds more per reply; both configurable and
  visible in the cost dashboard.
- (−) Customer profiles and media widen the personal data processed; covered by notice, retention
  and erasure, and a new sub-processor for audio (ADR-012).
- (−) Draft/published configuration and the simulator add API surface and panel screens.
