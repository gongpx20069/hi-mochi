# Mochi Product Requirements

## 1. Product definition

Mochi is an Android-only, voice-first AI desktop pet. The phone is Mochi's face,
planner, and local agent host. The product is implemented directly with native
Android APIs and does not require an account or an official Mochi cloud
runtime.

## 2. Product principles

1. Local-first: persona, conversation, calendar, todo, and settings stay on the
   device by default.
2. BYOK: intelligent chat uses Azure OpenAI, OpenAI, or a user-configured
   OpenAI-compatible provider.
3. Native-first: UI, voice, persistence, tools, permissions, and background
   work use Android platform components.
4. Voice-first: speech can change data and move the UI to the most relevant
   Mochi surface.
5. Explicit side effects: destructive or sensitive actions require clear user
   intent and platform confirmation where appropriate. Agent Browser is the
   explicit exception: its enabled Tools execute without Mochi approval prompts.

## 3. Core experiences

### 3.1 Mochi face

- Full-screen animated Mochi face.
- Listening, thinking, speaking, and emotional states.
- Touch and gesture navigation remain available alongside voice navigation.
- Focus mode hides navigation and system chrome, keeps the screen awake, and
  dedicates the full display to the active Home presentation.
- Focus mode enters an optional low-power standby presentation after 30 seconds
  of idle time by default. Standby uses pure black with a minimal Mochi, local
  date, and large local time, and restores the prior Home presentation on
  touch or voice activity. Settings may disable standby or select a longer
  idle delay.

### 3.2 Voice interaction

1. Wake with `Hi Mochi`, microphone, media button, or text.
2. Recognize speech with Android `SpeechRecognizer` by default, or with an
   optional user-configured iFlytek/Azure Speech connection.
3. Run the local agent loop against the user's BYOK model.
4. Execute local tools.
5. Apply a validated UI directive.
6. Speak the final response with Android `TextToSpeech`.

The wake word is enabled by default after Android permissions are granted.
Users can explicitly disable it in Settings.
Cloud STT is an optional reliability recommendation, never a prerequisite.
Android `TextToSpeech` remains the speech-output implementation.

### 3.3 Mochi Planner

Mochi owns its calendar and todo data in Room.

- Home can transform into current date-time or local weather presentations.
- Trusted Agent cards can transform Home or render inline in Talk without
  allowing model-generated code or arbitrary UI components.
- Today view: events and todos for the current date.
- Month view: dates containing events or todos are visibly marked.
- Day view: reusable Today/selected-date agenda with events, active todos, then
  completed todos.
- Todos always have a scheduled date. Missing dates default to today with an
  explicit notice to the user.
- Reminders: local notifications and exact alarms where permitted.

Android Calendar Provider is not the source of truth. Future system-calendar
support is an optional import/export adapter.

### 3.4 Voice-driven surfaces

- Questions about the current time/date transform Home into a live clock/date.
- Questions about current weather, temperature, or humidity transform Home into
  a local weather card.
- Explicit current-position, nearby, and current-origin route requests may use
  Android's permission-gated device location. The location Tool is independently
  disableable and exposes coordinates to the configured LLM only when called.
- Questions about another date open that date in Mochi Calendar.
- Questions about todos open the relevant day or Todo view.

### 3.5 Public web research

- Mochi can search current public information through Bing.
- Mochi can search WeChat official-account articles through Sogou Weixin.
- Travel Planning combines Amap route and destination context with
  foreground-only Agent Browser research on public no-login train and flight
  pages.
- Ticket research uses visible website controls only, never undocumented
  endpoints, account login, CAPTCHA bypass, passenger identity, booking, or
  payment.
- Both search engines, result pages, and selected sources are operated through
  the built-in Agent Browser rather than dedicated search or fetch Tools.
- Mochi opens selected source pages and bases answers on bounded readable text,
  not search snippets alone.
- Web pages are untrusted input; local/private network targets and executable
  page behavior are not exposed to the Agent.
- Mutations highlight the created or changed item.
- General educational discussion about time or calendars does not navigate.

### 3.5 Provider onboarding

- A new installation without a configured provider opens Settings directly.
- Azure OpenAI is a first-class option with resource endpoint, deployment
  name, API version, and API key fields.
- OpenAI and custom OpenAI-compatible endpoints remain available.
- Settings is always discoverable from the primary app shell.
- API keys are never displayed again after saving.

### 3.6 Agent Browser

- The Agent may use one lightweight Android System WebView through
  five typed Tools: read, navigate, click, input, and scroll.
- Browser allocation is lazy and scoped to one conversation turn.
- After the LLM produces the final response, Mochi destroys the per-turn
  browser before TTS begins. Cancellation, timeout, and failure use the same
  cleanup path.
- Web content is untrusted evidence and cannot issue Agent instructions.
- Browser Tool actions are allowed by default and execute without Mochi
  approval prompts or confirmation waits.
- Home presents an active session as a trusted live Browser Card. Other
  surfaces remain unchanged and show only the existing Tool pipeline status.
- Entering the background does not pause or cancel Browser execution. A
  user-visible Agent notification exposes status and Stop while Browser Tools
  continue executing.
- The Tools surface groups the Agent Browser provider switch and all five
  individual Browser Tool switches in one card.

### 3.7 Subagents

- The Main Agent may delegate a self-contained task to Researcher or Analyst.
- Delegation is serial: the Main Agent pauses, exactly one Subagent runs, and
  the Main Agent resumes with its structured result.
- One top-level interaction permits at most two delegations. Subagents cannot
  delegate, run in parallel, or continue in the background.
- Researcher receives enabled Browser Tools, read-only MCP Tools, and Skills.
  Analyst receives the same capabilities plus sandboxed JavaScript.
- Subagents receive no parent conversation history, memories, or persona.
- Subagent Browser work reuses the current per-turn session. Home keeps the
  Browser Card visible and identifies the active Subagent.

### 3.8 Skills

- Skills has Installed and Explore surfaces.
- Built-in Mochi skills are visible and read-only.
- skills.sh market skills can be installed, enabled, edited, updated, and
  removed locally.
- Installed skills retain source URL, upstream version/digest, local digest,
  modification state, and last update-check time.
- Market skills are disabled immediately after installation until the user
  explicitly enables them.
- Skills follow the Agent Skills `SKILL.md` format. Only enabled Skill metadata
  appears in the initial Agent context; full instructions load on demand.
- Skill enablement never bypasses provider or individual Tool switches. A
  disabled Tool is unavailable even when an enabled Skill refers to it.
- Bundled scripts and dependencies are never executed automatically.

### 3.9 Language

- App language defaults to the Android system language.
- Chinese system locales use the Chinese UI; every non-Chinese system locale
  uses the English UI.
- Settings can explicitly select Follow system, Chinese, or English.
- Default model-facing instructions, including SOUL, USER, AGENTS, Tool
  contracts, and the system prompt, remain English in every UI language.

## 4. Local agent

The agent uses:

- app-private SOUL, USER, and AGENTS files;
- configurable recent local conversation, default 20 complete turns;
- direct SQLite memory recall with neighboring context;
- current local date, time, locale, and timezone;
- explicit tool schemas;
- structured final output containing reply, emotion, and optional UI directive.

SOUL, USER, AGENTS, and conversation-context settings are local configuration.
They remain editable and saveable before any LLM provider is connected.

The prompt does not include synthetic current emotion, current app surface,
owner name, affinity, interaction count, or unlocked abilities. There is no
HEARTBEAT persona file and no separate optional system prompt.

The model cannot directly access Android services, Room, files, or navigation.
All side effects pass through validated local tools.

## 5. Data requirements

Calendar events support title, notes, start/end, all-day state, timezone,
recurrence, location, reminder, and timestamps.

Todos support content, status, priority, scheduled date, due time, reminder,
completion time, and timestamps. Todos appear in the reusable day planner for
their scheduled date.

Local export/import must cover persona, conversation, events, todos, and
settings without exposing API keys.

## 6. Non-goals

- iOS or desktop support.
- Mandatory login, quota, redeem code, or Mochi cloud sync.
- Silent phone calls, SMS sending, contact mutation, or destructive deletion.
- System calendar as required storage.
- Arbitrary shell or filesystem access from model-generated scripts.

## 7. Acceptance criteria

- A release APK builds from the native Gradle project.
- App works without login or a Mochi-hosted backend.
- Wake, STT, agent loop, tools, TTS, and cancellation work on a real device.
- Calendar and todo CRUD work entirely against Mochi-owned Room data.
- Voice requests navigate to Today, Calendar Day, or Todo according to the
  documented policy.
- Process death and app restart preserve planner and conversation state.
- Language selection persists and changes UI, notifications, STT, TTS, and
  date formatting without translating model-facing instructions.
- Automated tests cover date resolution, navigation policy, tool validation,
  Room migrations, and agent cancellation.
