# ADR-012 — Voice messages transcribed by the OpenAI API

- **Status:** Accepted
- **Date:** 2026-10-29
- **Complements:** ADR-005 (LLM provider), ADR-011 (humanized service)

## Context

Voice messages are very common in Brazil. Claude reads text and images but does not accept audio,
so a speech-to-text step is needed before the conversation engine.

## Decision

- A `SpeechToText` port in `platform-core`, implemented by an adapter for the OpenAI transcription
  API (`gpt-4o-mini-transcribe`, pt-BR), in a new module `stt-openai` that depends only on the core.
- The inbound worker downloads the audio from the Cloud API media endpoint, transcribes it and
  stores the transcription with the message; the audio itself is not kept. The history shows it
  as "[áudio] <transcrição>".
- A failure, a too long audio (default limit 2 minutes) or an empty transcription becomes a
  natural request to write, or a handoff if it repeats.
- Cost of every transcription is recorded per tenant like `llm_call`; the key is `OPENAI_API_KEY`
  (environment variable only). Without it, audio keeps today's behavior (ask to write).
- OpenAI becomes a sub-processor: privacy policy and the DPA with the restaurant must list it;
  API data is not used for training under OpenAI's business terms.

## Alternatives considered

- **Whisper on the VPS:** no third party and no cost per use, but CPU-heavy on a small VPS and
  slower per audio.
- **Google Speech-to-Text:** similar quality; needs a Google Cloud account.
- **Postpone:** keeps asking customers to type, which the humanized goal (D-012) rules out.

## Consequences

- (+) Customers can talk the way they do with people.
- (+) Provider behind a port, replaceable.
- (−) One more key, provider and sub-processor; cents per minute of audio.
