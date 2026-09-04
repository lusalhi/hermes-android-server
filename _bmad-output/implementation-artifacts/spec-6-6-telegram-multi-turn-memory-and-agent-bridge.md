---
title: '6-6-telegram-multi-turn-memory-and-agent-bridge'
type: 'feature'
created: '2026-09-04'
status: 'done'
baseline_commit: 'a7fddc3110e52b47a5dbb009b78cfdf0164667c6'
review_loop_iteration: 0
context:
  - _bmad-output/implementation-artifacts/epic-5-context.md
  - _bmad-output/implementation-artifacts/epic-6-context.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The native Telegram Bot gateway in `TelegramGatewayManager` operates as a single-turn stateless chatbot with no conversation history across messages, provides no visual typing feedback while processing queries, and does not bridge to the local Hermes Agent daemon on port 8000, preventing autonomous tool execution and contextual interactions from Telegram.

**Approach:** Upgrade `TelegramGatewayManager` with thread-safe sliding-window multi-turn conversation memory per `chat_id` (including `/clear` and `/reset` commands), periodic `sendChatAction("typing")` status feedback during query processing, and a hybrid query router that attempts local Hermes Agent REST daemon execution on port 8000 first before seamlessly falling back to direct cloud LLM providers.

## Boundaries & Constraints

**Always:**
- Maintain rolling multi-turn conversation history per Telegram `chat_id` up to 10 conversational turns (20 messages max) to prevent memory growth and token exhaustion.
- Provide `/clear`, `/reset`, and `/new` Telegram commands that purge the session conversation history for that `chat_id`.
- Dispatch Telegram `sendChatAction(chatId, "typing")` periodically (every ~4 seconds) in a background coroutine while waiting for local daemon or LLM responses.
- When `gateway.restApi.enabled == true`, attempt routing messages first to the local Hermes daemon at `http://127.0.0.1:$port/v1/chat/completions` with a short connection timeout (e.g. 2500ms).
- If the local daemon is offline, unconfigured, or returns an error, seamlessly fall back to direct cloud LLM completion (`queryLlmDetailed`) and log the fallback in `LogStreamer`.
- Ensure all session state operations on `conversationHistory` are thread-safe (`ConcurrentHashMap`).
- Maintain full backward compatibility for existing admin whitelisting, custom LLM providers, and commands (`/ping`, `/start`, `jam berapa`).

**Ask First:**
- Persisting Telegram chat session histories to persistent SQLite database across app process restarts (current scope is in-memory session cache).

**Never:**
- Never block the coroutine polling loop during typing indicators or HTTP timeouts.
- Never crash the polling loop on local daemon network connection errors or timeouts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Multi-turn conversation | Turn 1: "My name is Budi" -> Turn 2: "What is my name?" | Turn 2 query includes Turn 1 history; LLM or agent answers "Your name is Budi" | History trimmed to max 10 turns |
| Reset session memory | User sends `/clear`, `/reset`, or `/new` | Chat history for that `chat_id` is emptied; sends confirmation reply | Idempotent on empty history |
| Typing feedback during generation | User sends question taking 3+ seconds | `sendChatAction("typing")` sent immediately and refreshed every 4s until reply sent | Ignores network error on action |
| Local daemon active on port 8000 | `restApi.enabled = true` and daemon running | Message routed to `http://127.0.0.1:8000/v1/chat/completions`; agent response returned | Falls back if local returns non-200 |
| Local daemon offline | `restApi.enabled = true` but daemon stopped | Local connection fails fast; seamlessly falls back to direct cloud LLM | Logs warning and sends cloud reply |
| Unauthorized Telegram sender | Sender ID not in `adminUserIds` | Returns "Akses Ditolak" immediately; does not touch history or query LLM | Rejects without leaking tokens |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/engine/TelegramGatewayManager.kt` -- Add `TelegramChatMessage`, `conversationHistory`, `sendChatAction`, local daemon routing with fallback, multi-turn messages payload, and `/reset` handling.
- `app/src/test/java/com/hermes/node/engine/TelegramGatewayManagerTest.kt` -- Unit tests covering multi-turn history accumulation, sliding window truncation, `/reset` command, typing indicator dispatch, and local daemon fallback.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/engine/TelegramGatewayManager.kt` -- Add `TelegramChatMessage` model and in-memory `conversationHistory` per chat ID with sliding window limit (10 turns / 20 messages).
- [x] `app/src/main/java/com/hermes/node/engine/TelegramGatewayManager.kt` -- Add `sendChatAction(botToken, chatId, action = "typing")` and launch typing indicator coroutine loop during response generation.
- [x] `app/src/main/java/com/hermes/node/engine/TelegramGatewayManager.kt` -- Implement local Hermes Agent daemon routing (`http://127.0.0.1:$port/v1/chat/completions`) with automatic fallback to `queryLlmDetailed`.
- [x] `app/src/main/java/com/hermes/node/engine/TelegramGatewayManager.kt` -- Add `/clear`, `/reset`, and `/new` command handling to purge session history.
- [x] `app/src/test/java/com/hermes/node/engine/TelegramGatewayManagerTest.kt` -- Add unit tests for multi-turn history, `/reset` command, typing indicator, and local daemon routing/fallback.

**Acceptance Criteria:**
- Given an active Telegram chat session, when sequential conversational turns occur, then prior messages are included in subsequent query payloads up to 10 turns.
- Given a Telegram chat session with active history, when the user sends `/reset` or `/clear`, then history is cleared and a confirmation message is sent.
- Given a user query, while waiting for the response, then `sendChatAction` with `"typing"` is dispatched to Telegram.
- Given `restApi.enabled == true`, when a query arrives, then the manager attempts local daemon execution first, falling back to direct LLM if unreachable.

## Spec Change Log

_None._

## Design Notes

Local daemon dispatch payload matches OpenAI Chat Completions standard (`POST /v1/chat/completions`), enabling direct compatibility with upstream Hermes Agent's REST server as well as cloud providers.

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with all unit tests passing.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` generating debug APK.

## Suggested Review Order

**Conversation Memory & Session State**

- Implements thread-safe sliding-window history, staging mechanism, and session reset commands
  [`TelegramGatewayManager.kt:73`](../../app/src/main/java/com/hermes/node/engine/TelegramGatewayManager.kt#L73)

**Interactive Typing Indicator**

- Dispatches periodic `sendChatAction("typing")` during response generation with short timeout
  [`TelegramGatewayManager.kt:285`](../../app/src/main/java/com/hermes/node/engine/TelegramGatewayManager.kt#L285)

**Local Daemon Routing & Cloud Fallback**

- Routes prompts to local Hermes Agent daemon on port 8000 with seamless cloud LLM fallback
  [`TelegramGatewayManager.kt:511`](../../app/src/main/java/com/hermes/node/engine/TelegramGatewayManager.kt#L511)

**Message Delivery & Chunking**

- Handles Telegram 4096-character limit chunking and error isolation
  [`TelegramGatewayManager.kt:837`](../../app/src/main/java/com/hermes/node/engine/TelegramGatewayManager.kt#L837)

**Peripherals & Verification**

- Verifies multi-turn history, typing action, daemon routing, Anthropic role alignment, and chunking
  [`TelegramGatewayManagerTest.kt:180`](../../app/src/test/java/com/hermes/node/engine/TelegramGatewayManagerTest.kt#L180)

