# Native Android Roadmap

This plan is ordered. Do not start later phases by bypassing missing contracts
or verification in earlier phases.

## P0 - Repository harness

- [x] Define native Android product direction.
- [x] Define architecture, tools, planner, and voice-navigation contracts.
- [x] Add root agent engineering instructions.
- [x] Create `android/` Gradle project and wrapper.
- [x] Add format, lint, unit-test, and release tasks.
- [x] Add CI and initial architecture dependency checks.
- [ ] Add instrumentation/Compose test execution after the first interactive
  feature is implemented.

## P1 - Native shell and surfaces

- [x] Compose theme and application shell.
- [x] Typed `MochiSurface` state and reducer baseline.
- [ ] Navigation Compose integration.
- [x] Face, Today, Calendar Month, Calendar Day, and Todo surfaces.
- [x] Conversation and Settings surfaces.
- [x] Connect Today, Calendar Day, and Todo to Room through ViewModel.
- [x] Add local todo creation and completion interactions.
- [ ] Gesture and Android predictive-back behavior.
- [ ] Accessibility and Chinese/Japanese/English localization.

## P1 - Planner

- [x] Room Schema v2 for calendar, todo, and editable market skills.
- [x] Event CRUD, overlap queries, timezone, recurrence-rule persistence, and
  validation.
- [ ] Recurrence expansion and Android reminder scheduling.
- [x] Todo scheduled date, due time, completion, priority, and filters.
- [x] Unified day agenda showing events and dated todos.
- [ ] Local export/import.

## P1 - Agent

- [x] Kotlin BYOK OpenAI-compatible HTTP client and protocol models.
- [x] Built-in/skills.sh management.
- [x] Agent Skills metadata discovery and on-demand activation.
- [x] App-private SOUL/USER/AGENTS files.
- [x] Configurable recent conversation context, default 20 turns.
- [x] SQLite conversation persistence and ICU-tokenized FTS4 memory recall.
- [x] Typed ToolRegistry, JSON schemas, and result envelopes.
- [x] Mochi calendar/todo CRUD tools with destructive confirmation.
- [x] Permission-gated current-location and weather Tools with explicit
  WGS-84/GCJ-02 coordinate contracts.
- [x] `navigate_mochi_ui` and deterministic directive validation.
- [x] Wire validated UI directives into the agent response loop.
- [x] Multi-round Tool loop, cancellation, payload bounds, one bounded final
  JSON repair, and protocol tests.
- [x] Keystore-backed provider settings and native settings UI.
- [x] Connect text conversation UI to `AgentOrchestrator`.
- [x] Persist conversation history locally and exclude the recent window from
  long-term recall.

## P1 - Voice

- [x] Native wake foreground service.
- [x] Kotlin sherpa wake/VAD wrapper.
- [x] Push-to-talk SpeechRecognizer and TextToSpeech.
- [x] Reject stale STT/Agent callbacks when a new interaction starts.
- [x] Audio-focus coordinator.
- [x] Media-button and lock-screen trigger support.
- [ ] Voice navigation acceptance tests.

## P2 - Release readiness

- [x] Add monotonic manual pipeline and local APK publication paths.
- [ ] Real-device release validation.
- [ ] Performance, battery, and process-death validation.
- [x] Signed release APKs with per-ABI and universal download variants.

## P2 - Agent Browser

- [x] Add one grouped Agent Browser provider card with five Tool switches.
- [x] Add the visible per-turn Android System WebView session.
- [x] Implement bounded semantic snapshots and temporary element references.
- [x] Implement read, navigate, click, input, and scroll Tools.
- [x] Migrate Bing and Sogou Weixin research to Browser Tools and remove
  `search_web` and `fetch_web_page`.
- [ ] Add default-allow browser policy, blocked-platform-boundary, renderer
  recovery, background execution, and cleanup tests.

## P2 - Serial Subagents

- [x] Add isolated Researcher and Analyst roles.
- [x] Add one-at-a-time delegation with a two-child per-run limit.
- [x] Restrict children to Browser, read-only MCP, Skills, and Analyst JS.
- [x] Reuse the active Browser session and identify the child on its card.
- [ ] Add end-to-end provider and real-device Subagent validation.

## P2 - Optional signed extensions

- [x] Add the versioned `:extension-api` Binder contract.
- [x] Add same-signer explicit package discovery, binding, cancellation, and
  Tool adaptation to the base app.
- [x] Add the no-launcher optional Mi Home extension APK and QR connection UI.
- [x] Add encrypted Xiaomi sessions, region discovery, MIoT state/control, and
  user-selected homes/devices.
- [x] Add common MIoT lights, switches/plugs, climate/air devices, curtains,
  and read-only sensors.
- [x] Add television controls, camera state/settings, scale device status, and
  manual scenes.
- [x] Add foreground-only latest camera event images and the deterministic
  trusted Camera Snapshot card.
- [x] Add Tool-readiness-gated Mi Home Smart Home Skill activation.
- [x] Add default-off, same-run model image input with bounded normalization and
  no history, memory, log, export, Scheduled Agent, or Subagent persistence.
- [x] Build, sign, validate, checksum, and publish the universal extension APK
  with the normal Android Release.
- [ ] Validate Xiaomi QR authorization, region discovery, device control, and
  camera event retrieval against real accounts and devices.
- [ ] Validate in-place base/extension installation, Binder recovery, and
  configuration UX on real Android hardware.
