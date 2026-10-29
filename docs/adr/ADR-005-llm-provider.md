# ADR-005 — LLM provider behind our own port; start with the Claude API

- **Status:** Accepted; audio goes to a separate speech-to-text provider ([ADR-012](ADR-012-audio-transcription.md))
- **Date:** 2026-10-28

## Context

The conversation engine needs an LLM with reliable tool calling, good Portuguese and
predictable cost per tenant. We want to change provider or model without touching the engine.

## Decision

- **`LlmClient` port in `platform-core`** with neutral types: messages, tool definitions (JSON
  Schema), response blocks (text / tool call), `stopReason`, `usage` (input, output, cache
  read/write tokens). The tool loop lives in the engine, not in the adapter — the engine
  controls limits, validation and logging of every call.
- **`llm-anthropic` adapter** with the official SDK `com.anthropic:anthropic-java`.
- **Model configurable per tenant** (`llm.model` in `tenant.config`). Initial default:
  `claude-opus-5-5` with low `effort` for chat turns. Switching to `claude-sonnet-5-5` or
  `claude-haiku-5-5` is a cost/quality decision to be made with the M5 eval set, measuring
  cost per completed conversation, not per request.
- **Prompt caching:** a stable, deterministic prefix (tools → pack prompt → tenant
  configuration/FAQ), volatile state (cart, current time) at the end. Check
  `cache_read_input_tokens` in the metrics.
- **Current API specifics the adapter must respect:** no forced `tool_choice` (`any`/`tool`) —
  use `auto` + `strict` tools; thinking cannot be turned off in current models (controlled by
  `effort`); do not send `temperature`/`top_p`; handle `stop_reason = refusal` and enable
  server-side fallbacks.
- **Cost:** every call writes `llm_call` (tenant, conversation, model, tokens, cost computed
  from a price table versioned in configuration).
- **Privacy:** send the LLM only what is needed (no full phone number); use an API account with
  commercial terms (data not used for training) and list Anthropic as a sub-processor.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Spring AI as the abstraction** | Useful, but a common abstraction tends to lag provider-specific features (caching, effort, fallbacks, refusal). Our port is small; the adapter may use Spring AI later if it pays off. |
| **LangChain4j** | Same argument; brings more than we need (memory, agents) and hides the loop. |
| **Calling the HTTP API directly** | The official SDK already handles retries, types and streaming. |
| **Self-hosted model** | Tool-calling quality in pt-BR and GPU operating cost do not pay off at this volume. |

## Consequences

- (+) Changing provider/model is isolated in the adapter and the configuration.
- (+) LLM cost visible per tenant from day one.
- (−) Advanced features of a provider must be exposed in the port deliberately.
