# Native Android Agent Technical Design

This document defines the native Android implementation.

## 1. Stack

| Concern | Target |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Navigation | Navigation Compose |
| State | ViewModel + StateFlow |
| Concurrency | Coroutines and structured concurrency |
| Persistence | Room |
| Preferences/secrets | DataStore + Android Keystore |
| HTTP | OkHttp |
| Background work | WorkManager |
| Precise reminders | AlarmManager |
| Wake word/VAD | sherpa-onnx Android AAR |
| STT | Android SpeechRecognizer by default; optional iFlytek/Azure Speech |
| TTS | Android TextToSpeech |

## Releases and Provider sharing

Native releases use monotonic `1.0.x` versions and derive Android
`versionCode` as `10000 + x`. Git tags are the shared version authority. The
manual Android workflow and local publisher both inspect remote `v1.0.x` tags,
allocate the next patch, inject that version into Gradle, verify the signed
APK's embedded version, and publish the APK plus its SHA-256 file. A release
publisher atomically reserves the remote tag immediately before creating the
Release; a collision fails rather than updating or reusing a version. Reserved
tags are never automatically deleted, so interrupted publication may skip a
patch number but cannot roll back or overwrite a released version. At startup,
Mochi checks only the latest stable release and opens its GitHub page after
explicit user confirmation; Android remains responsible for APK installation.

Provider sharing covers only the complete LLM and Speech Provider runtime
configuration. Mochi serializes those credentials, encrypts them with a fresh
AES-256-GCM key, and places ciphertext and key in a `mochi://provider/import`
link. No password or backend is required, but possession of the complete link
grants the API access and quota of the sender. Import requires confirmation and
replaces the receiver's LLM and Speech Provider configuration. Persona,
memories, planner data, Tools/MCP credentials, and Android permissions are
excluded.

## 2. Runtime

```text
Wake / mic / text
  -> VoiceSessionController
  -> selected STT path
  -> AgentOrchestrator
  -> PromptBuilder + BYOK LLM
  -> ToolRegistry
  -> Room / Android capability adapters / Agent Browser
  -> validated AgentResponse
  -> SurfaceNavigator
  -> TextToSpeech
```

The Main Agent registry adds `delegate_agent` for each top-level run. Its
request-scoped `SerialSubagentCoordinator` permits at most two delegations and
holds a mutex while one Researcher or Analyst child runs. The parent coroutine
waits for the child, so cancellation propagates naturally and there is no
parallel, nested, or background Subagent execution.

Each child uses a new `AgentOrchestrator` with only the delegated task, provider
configuration, Tool execution context, enabled Skill metadata, and its fixed
role instructions. Parent history, memories, persona, navigation sinks, and
`delegate_agent` are absent. Researcher receives enabled Browser Tools and
read-only MCP Tools; Analyst additionally receives sandboxed JavaScript. Child
research is bounded to 30 Tool rounds. Reaching that bound returns a typed Tool
error to the Main Agent instead of failing the entire conversation. Both child
roles are instructed to use their isolated context and larger round budget for
deeper investigation than the Main Agent performs directly.

Main and child orchestrators emit structured `MochiAgent` Logcat diagnostics
for run start, model rounds, Tool start/finish, completion, cancellation, and
failure. Events include a run ID, actor (`main`, `researcher`, or `analyst`),
round and Tool counters, Tool name/status, duration, and exception type.
Diagnostics never include prompts, delegated tasks, Tool arguments/results,
provider responses, credentials, or endpoint URLs.

Every interaction has an immutable session ID. Starting a new interaction
cancels the old coroutine scope. Late STT, model, tool, and TTS callbacks must
not update state when their session ID is stale.

Agent Browser is a Tool provider backed by one Android System WebView per
serialized Agent turn. Foreground runs may use all five Browser Tools;
Scheduled Agent runs receive the read-only navigate/read/scroll subset.
The first Browser Tool call in a turn lazily creates the session.
`browser_read`, `browser_navigate`, `browser_click`, `browser_input`, and
`browser_scroll` operate bounded viewport-Markdown snapshots with a separate
interactive element list and temporary references.
Home renders the active session through a trusted runtime Browser Card. Other
surfaces remain unchanged and expose only the global Tool pipeline state.
Subagents reuse this same top-level WebView and do not begin or close another
browser turn. While a child is active, the Browser Card identifies Researcher
or Analyst; the label clears when child execution finishes or is cancelled.
After the LLM produces its final response, the Orchestrator closes the browser
before TTS. Structured cleanup also runs on cancellation, timeout, and failure.
App backgrounding does not close or pause the session. A foreground Agent
execution notification keeps the interaction discoverable and cancellable.
Browser Tool actions execute without Mochi confirmation gates or user waits.
See `AGENT_BROWSER.md`.

Wake and media triggers preserve the selected `MochiSurface`. A typed pipeline
observer reports skill preparation, provider thinking, tool execution, final
summarization, and speaking to a global Compose indicator; listening remains
derived from `VoiceRuntimeState`. Pipeline state is reset on cancellation,
failure, or completion.

Wake capture pauses only while conversation STT owns the microphone. It resumes
as soon as a final transcript is accepted and remains active through Agent work,
tool execution, summarization, and TTS. Saying "Hi Mochi" during those phases
cancels the current interaction, stops speech playback, rejects stale callbacks,
and starts a fresh listening session. Android recognition endpointing owns the
default listening window; optional iFlytek or Azure Speech uses one temporary
local PCM capture. iFlytek streams PCM during capture, uses provider endpoint
prediction first, and retains Silero VAD as a local fallback. Azure uploads
after local endpoint detection. Silero VAD remains the local wake-stage
voice-activity filter and must not capture the microphone concurrently with
conversation STT.

Weather tool output is retained for the active interaction. If the model fails
after weather retrieval, the ViewModel creates a deterministic localized
weather summary from that structured result, sends it to TTS, and preserves the
same follow-up Listening behavior as a normal successful voice turn.

The current native slice provides push-to-talk from the Conversation surface.
`AndroidVoiceRuntime` owns the selected STT path and Android `TextToSpeech`,
publishes a bounded typed state, releases services with the Activity, and
forwards the best available transcript to the ViewModel. Android
`SpeechRecognizer` remains the zero-configuration default. Settings may
optionally configure encrypted iFlytek AppID/APIKey/APISecret or an Azure
Speech endpoint/key. Cloud STT records one bounded utterance, retries the same
temporary audio for transient failures, and deletes it after completion. If
Android emits useful partial text but later returns `NO_MATCH` or
`SPEECH_TIMEOUT`, Mochi preserves that partial text instead of discarding the
entire utterance. Android failures recommend—but never require—optional cloud
speech setup.

The native wake layer uses the pinned sherpa-onnx 1.13.2 AAR, downloaded from
the upstream release and verified by SHA-256 during the build, plus the int8
Zipformer KWS models, Silero VAD, a 16 kHz PCM16 `AudioRecord` worker, and a
microphone foreground service. Wake capture pauses and confirms completion
before SpeechRecognizer or cloud audio capture starts. Audio focus is
transient-exclusive for STT and transient-ducking for TTS. Wake AudioRecord
enables platform acoustic echo cancellation when available so local keyword
detection can remain active while Mochi speaks.

Wake capture defaults to enabled and starts when the Activity reaches the
resumed foreground state, after requesting required runtime permissions. The
preference is persisted so an explicit disable remains disabled. Deferring
startup until resume also avoids consuming the initial attempt while Android is
still presenting a secure lock screen.

An active Android `MediaSession`, notification Talk action, headset media keys,
and a lock-screen-visible wake notification all enter the same typed voice
path. Background wake detection uses a user-mediated notification/PendingIntent
instead of relying on Android-blocked background Activity launches. The
implementation deliberately does not use the legacy silent-loop playback hack
to steal media-button ownership.

Mochi does not retain microphone audio. Android's selected recognition service
may process audio remotely depending on the device. Optional iFlytek/Azure STT
uses app-private temporary audio that is deleted after transcription; fully
local conversation STT remains future work.

The Agent may return one optional `card_directive` with its final structured
reply. `AgentOrchestrator` retains successful JSON Tool evidence for that run,
binds typed local card fields from the evidence, and permits bounded general
content fields for successful web and MCP evidence. Invalid optional card
requests are discarded without discarding the text reply.
`CardPresentationPolicy` resolves Home, inline, or deferred placement before
the ViewModel updates Compose state. The Android renderer is a trusted local
catalog rather than model-generated Compose, HTML, or script. The complete
contract is documented in `CARD_PRESENTATION.md`.

Home and inline use the same `CardPresentation` and typed Action renderer. For
an individual message with a valid card, the card replaces that message's text
bubble; messages without cards remain normal text replies. Card reply text
remains the TTS, history, and rendering-failure fallback. Action targets are
bound to same-run evidence, and state changes are applied to every placement
sharing the card ID.

## 3. Agent response

```json
{
  "reply": "下周三是 8 月 5 日，你有两个待办。",
  "emotion": "neutral",
  "ui_directive": {
    "surface": "calendar_day",
    "reason": "other_date",
    "date": "2026-08-05",
    "section": "agenda",
    "highlight_ids": ["todo_123"]
  }
}
```

`ui_directive` is optional and treated as untrusted model output. The app
validates enum values, ISO dates, referenced IDs, and transition policy before
changing the UI.

## 3.1 Native agent loop

The native implementation now includes:

- serializable OpenAI-compatible chat messages, tool calls, and responses;
- OkHttp `/chat/completions` client with cancellation and bounded responses;
- `AgentOrchestrator` with 20 Tool rounds for the Main Agent;
- one bounded, Tool-disabled repair round when a provider final response does
  not satisfy the Mochi JSON contract;
- ToolRegistry execution and `role="tool"` continuation messages;
- strict final JSON parsing and NavigationPolicy application;
- bounded query, history, tool-result, and final-reply sizes.

Provider metadata and encrypted API-key material are stored in Preferences
DataStore. Android Keystore owns the AES-GCM key; Compose receives only
`hasApiKey`, and the plaintext key is decrypted only when constructing the
runtime `OpenAiProviderConfig`.

`OpenAiProviderConfig` distinguishes three authentication and URL modes:

- OpenAI uses `Authorization: Bearer <key>` and appends
  `/chat/completions` to the configured API base.
- Azure OpenAI uses `api-key: <key>` and constructs
  `/openai/deployments/{deployment}/chat/completions?api-version={version}`;
  its request body omits `model`.
- Custom providers use the OpenAI-compatible bearer flow.

Provider errors are bounded and redact the configured key. Endpoints must be
absolute HTTP(S) URLs without embedded credentials.

The native Conversation surface sends text through `AgentOrchestrator`, applies
validated UI directives, supports cancellation, and rejects stale results from
superseded interactions. Successful user/assistant messages are persisted in
Room. Settings controls the recent context in complete turns, defaults to 20,
and bounds it to 1-50.

The prompt builder assembles, in order: app-private `SOUL.md`, `USER.md`, and
`AGENTS.md`; direct SQLite memory recall; factual local date/time/timezone;
enabled Skill metadata; compact Tool/navigation/Card policy; and the final
structured-response contract. It does not inject synthetic emotion, current
surface, owner name, affinity, interaction count, or unlocked abilities.
Provider Settings contain connection details only; the former optional system
prompt is removed because `AGENTS.md` is the editable operational-instruction
source.

AppCompat application locales provide Follow system, Chinese, and English
selection. Empty application locales follow Android resources: Chinese system
locales resolve to Chinese while all other locales fall back to English.
The resolved UI locale also drives STT, TTS, notifications, and date
presentation. Prompt assembly and seeded SOUL/USER/AGENTS documents remain
English and are independent of the UI locale.
SOUL, USER, AGENTS, and conversation-context settings are also persisted
independently of LLM provider configuration and do not require a provider
connection to edit or save.

Memory search is local, lexical, and embedding-free. Android ICU word
boundaries produce normalized Chinese and non-Chinese terms after explicit
Latin/Han transition splitting. Chinese indexing also retains contiguous Han
bigrams and unigrams as recall fallbacks, while numeric runs remain searchable.
Room FTS4 stores the pre-tokenized terms in a dedicated index; each successful
turn writes message and FTS rows in one transaction. Database migration 4-to-5
creates the FTS table and rebuilds every existing message with the current ICU
tokenizer rather than copying stale normalized text. FTS selects a bounded
candidate set, which is retokenized consistently and ranked in Kotlin by query
coverage, term specificity, inverse candidate frequency, exact-phrase matches,
and a deliberately small recency boost that cannot override a materially
stronger lexical match.

Retrieval excludes IDs in the recent message window, selects up to four
matching memories, includes up to three neighbors on either side, deduplicates,
and returns chronological context. The Relevant memories section declares the
device timezone once as `NOTE:<timezone ID>`. Every recalled line then includes
its original instant rendered as local time with only the UTC offset, avoiding
repeated timezone IDs while still distinguishing historical context from the
current conversation. If no conversation match exists, only explicit
fact/summary memories are eligible for recency fallback.

Skills follow the Agent Skills format: a root `SKILL.md` with required `name`
and `description` frontmatter plus optional `license`, `compatibility`,
`metadata`, and experimental `allowed-tools`. The initial prompt contains only
enabled Skill metadata. `load_skill` activates full instructions on demand;
bounded resources are accessed relative to the Skill root. Disabled Skills are
absent from discovery and cannot be activated. A Skill never enables a Tool:
provider and individual Tool switches still determine ToolRegistry membership,
and activation reports missing required Tools. Android never runs downloaded
scripts or package-install instructions.

The unauthenticated Explore default parses the public skills.sh Trending (24h)
leaderboard and falls back to install-ranked public search if the page shape
changes. Public search exposes install counts but not favorites; Mochi labels
heat as a local derivation from the displayed 24-hour or all-time install count.

Current location and weather share one permission-gated Android location
provider. A suspendable permission gate allows the active Agent Tool call to
continue after the system location dialog returns. Last-known locations older
than five minutes are rejected. `get_current_location` returns WGS-84 plus a
locally converted GCJ-02 coordinate inside China, along with available
accuracy, capture time, age, and provider metadata. The configured LLM receives
that Tool evidence only when the Tool is called. Current weather uses the same
provider with Open-Meteo, caches results for ten minutes, and reduces latitude
and longitude to two decimal places before sending them to Open-Meteo.

Native public-web research uses the Agent Browser through the built-in Web
Search Skill. Bing is preferred for technical, official, news, global, and
authoritative sources. Sogou Weixin is preferred for explicit WeChat requests
and Chinese lifestyle or experience queries. The Agent operates the search
page and selected sources with the five Browser Tools; there are no dedicated
`search_web` or `fetch_web_page` schemas.

Browser navigation enforces the public HTTPS policy, rejects local/private
address ranges and credential-bearing URLs, and bounds semantic snapshots and
Tool output. Search and source-page content is explicitly treated as untrusted
data.

## 4. Planner storage

Room is authoritative.

```text
calendar_events
  id, title, description, start_at, end_at, all_day, timezone,
  recurrence_rule, location, reminder_at, created_at, updated_at

todos
  id, content, status, priority, scheduled_date, due_at, reminder_at,
  completed_at, created_at, updated_at
```

Store instants in UTC and preserve timezone IDs for display and recurrence.
Date-only values use ISO `LocalDate`. Room migrations must be tested from every
released schema version.

The day planner is shared by Today and calendar-date navigation. Todo queries
order active rows before completed rows. Creation normalizes a missing
`scheduledDate` to `LocalDate.now(clock)`; the Agent tool also returns
`scheduled_date_defaulted=true` and a user-facing notice when it applies.
Today additionally queries active todos scheduled on or before the current
date. Earlier items retain their original date and are labeled as carried over;
future items and completed historical items are not included.

## 5. Navigation policy

Navigation is a local deterministic policy, not an unrestricted model action.

1. Resolve relative dates using device time, timezone, and locale.
2. Validate the requested surface against the conversation intent.
3. Do not navigate for generic calendar/time knowledge.
4. Prefer Home date-time for the current time or date.
5. Prefer Home weather for current local weather, temperature, or humidity.
6. Prefer Today for today's events and todos.
7. Prefer Calendar Day for non-today dates.
8. Prefer Todo for undated or cross-date task lists.
7. Highlight only IDs returned by successful tools or present in local data.

## 6. Tool safety

- Validate all arguments before side effects.
- Use typed result envelopes.
- Require explicit confirmation for destructive or sensitive operations,
  except the documented default-allow Agent Browser Tool provider.
- Keep model API keys out of prompts, logs, exports, and JavaScript.
- Bound tool rounds, network response size, script time, and script output.
- Do not expose shell/process execution or arbitrary filesystem access.

## 7. Observability

Use structured logs with interaction ID, phase, duration, tool name, and result
code. Never log API keys, full prompts containing private persona data, contact
details, or conversation bodies in release builds.

Required metrics during development:

- wake-to-STT latency;
- STT duration;
- model/tool round count;
- time to first UI transition;
- TTS start latency;
- cancellation and stale-callback counts.

## 8. Verification

The native project must provide deterministic Gradle tasks for formatting,
lint, unit tests, instrumentation tests, and release assembly. Real-device
validation remains mandatory for wake word, audio focus, permissions,
notifications, exact alarms, process death, and OEM behavior.
