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
   intent and platform confirmation where appropriate. Enabled Agent Browser
   and Termux Tools execute without Mochi per-command approval prompts.

Optional Termux execution is unrestricted shell capability, not a sandbox.
It requires a separately installed, trusted Mochi Termux extension and Termux,
explicit Android permission, connection, and Mochi provider/Tool enablement.
Once enabled, commands execute automatically for foreground conversations,
all Scheduled Agents, and both Subagent roles. There is no per-command/run
approval, voice confirmation, or separate background authorization setting.
Each call still checks provider and Tool switches; disabling or disconnecting
blocks new calls without stopping submitted commands. Existing enabled
providers adopt this policy on upgrade; their individual Tool choices remain.
Command output used by the Agent is
sent to the selected model Provider. Installation,
authorization and manual interactive-terminal work may open another App;
ordinary commands run in the background without switching away from Mochi.

## 3. Core experiences

### 3.1 Mochi face

- Full-screen animated Mochi face.
- Listening, thinking, speaking, and emotional states.
- Touch and gesture navigation remain available alongside voice navigation.
- Focus mode hides navigation and system chrome, keeps the screen awake, and
  dedicates the full display to the active Home presentation. The same Focus
  mode control toggles the presentation into and out of full screen.
- Focus mode enters an optional low-power standby presentation after 30 seconds
  of idle time by default. Standby uses pure black with a minimal Mochi, local
  date, and large local time, and restores the prior Home presentation on
  touch or voice activity. Settings may disable standby or select a longer
  idle delay.

### 3.2 Voice interaction

1. Wake with `Hi Mochi`, microphone, media button, or text.
   A confirmed `Hi Mochi` wake receives a one-syllable spoken
   acknowledgement before listening; this feedback is not conversation data.
2. Recognize speech with Android `SpeechRecognizer` by default, or with an
   optional user-configured iFlytek/Azure Speech connection.
3. Run the local agent loop against the user's BYOK model.
4. Execute local tools.
5. Apply a validated UI directive.
6. Speak the final response with Android `TextToSpeech` by default, or the
   explicitly enabled synthesis service of the selected speech Provider.

The wake word is enabled by default after Android permissions are granted.
Users can explicitly disable it in Settings.
Cloud STT is an optional reliability recommendation, never a prerequisite.
Cloud synthesis is also optional and default-off. iFlytek and Azure Speech
reuse their recognition credentials through **Also use this provider for
speech synthesis**, with an optional voice ID. Enabling it sends assistant
reply text to that Provider; service/voice access and quota must be available.
Wake acknowledgements remain local Android speech, not cloud synthesis.
Voice selection offers named presets/catalogs, custom cloud IDs, and a fixed-text
preview without involving the Agent or conversation history. Preview does not apply
the draft voice until settings are saved; catalog entries are not a promise of free
service or account authorization.

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
- Every Provider share opens a selection step. The configured LLM and speech
  Providers are selected by default; Amap, Tencent Docs, and manual MCP Tool
  credentials default to unselected.
- Import requires one explicit confirmation, replaces only included
  connections, stores imported secrets with the receiver's Keystore-backed
  storage, and immediately enables the shared Providers and selected Tools.
- Notion OAuth, Mi Home sessions and device selection, Android permissions,
  persona, memories, and planner data are never shared.

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
  Both may also use connected, enabled Termux Tools without additional
  confirmation; shell changes must remain within the delegated task.
- Subagents receive no parent conversation history, memories, or persona.
- When a foreground request explicitly asks to view, describe, or analyze a
  camera event and provider image input is enabled, the Main Agent may attach
  the one host-validated image to one serial Subagent delegation by setting
  `include_image=true`. A dedicated no-Tool multimodal prepass converts it to
  bounded text observations presented as untrusted user-role evidence; the
  normal Subagent loop receives no Mi Home Tool, raw image, file descriptor,
  attachment URL, or reusable capability.
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
- A Skill's prerequisites are presented and evaluated as aggregate Tool groups
  such as Agent Browser, Amap Maps, Tencent Docs MCP, or the Mi Home extension,
  rather than as a list of raw Tool IDs. A group is ready only while its
  provider is installed when applicable, connected, enabled, and every Tool
  from that group required by the Skill is individually enabled. If a
  dependency later becomes unavailable, the saved Skill preference remains but
  the Skill is suspended from Agent discovery until readiness is restored.
- Bundled scripts and dependencies are never executed automatically.

### 3.9 Language

- App language defaults to the Android system language.
- Chinese system locales use the Chinese UI; every non-Chinese system locale
  uses the English UI.
- Settings can explicitly select Follow system, Chinese, or English.
- Default model-facing instructions, including SOUL, USER, AGENTS, Tool
  contracts, and the system prompt, remain English in every UI language.

### 3.10 Optional signed extensions

- Mochi may expose optional Android capabilities through separately installed
  APK extensions rather than increasing every base APK.
- The first extension is the optional Mi Home connector. It has no launcher
  entry and is installed, connected, enabled, updated, and disconnected from
  the Mochi Tools surface.
- The base app accepts only extension packages signed by the same trusted
  release certificate and implementing the versioned Mochi Extension API.
  Installing an extension never enables it or grants device access implicitly.
- Mi Home connection uses a QR code scanned and confirmed through an already
  authenticated Mi Home app. Mochi never asks for or stores the Xiaomi account
  password.
- Mi Home is explicitly labeled as an unofficial cloud integration. It may
  stop working when Xiaomi changes undocumented account or device interfaces.
- After connection, the Mi Home provider remains disabled by default while
  every discovered child Tool defaults to enabled. Enabling the aggregate
  provider therefore makes all child Tools available unless the user
  explicitly disables individual Tools.
- The initial Mi Home scope covers:
  - common specification-driven devices such as lights, switches, plugs,
    fans, air conditioners, air purifiers, humidifiers, and curtains;
  - read-only temperature, humidity, air-quality, contact, motion, and battery
    sensor state when exposed by the device;
  - television state, input, volume, mute, navigation, and playback controls
    when exposed by the device's MIoT specification;
  - camera state and selected settings, plus the newest available motion or
    doorbell event image;
  - scale identity, connectivity, and battery state when exposed;
  - manually triggered scenes selected by the user.
- Camera live view, playback, two-way audio, PTZ, current-frame capture, memory
  card mutation, robot-vacuum maps, arbitrary private device protocols, locks,
  alarms, garage doors, and scale body measurements are outside the initial
  scope.
- Camera event images are foreground-only ephemeral attachments. With the
  current provider's explicit camera-image setting enabled, one host-validated
  image may be sent to that provider in the same foreground Main-Agent run for
  a user's explicit view, description, or analysis request. Images are never
  persisted to conversation history, memory, logs, export, or sharing, copied
  to the gallery, or exposed to scheduled runs. The Main Agent may hand the
  same normalized image to at most one explicitly requested serial Subagent in
  the same foreground run.
- Xiaomi session credentials remain inside the extension's Keystore-backed
  storage and never enter Mochi prompts, logs, exports, provider sharing, or
  Binder payloads.

### 3.11 AgentLink shared coding chats

- Mochi is the primary voice/text controller for authorized shared AgentLink
  CLI/App chats, with explicit native user authorization and one return to Mochi.
- Tools always shows **Connect/Open AgentLink**, connection status, refresh,
  access manager/revoke, provider and three individual Tool switches, and
  trusted linked-chat jumps. Installation alone never authorizes access.
- Exactly `agentlink_workspace`, `agentlink_chat`, and `agentlink_control`
  plus the disabled-by-default AgentLink built-in Skill provide discovery,
  workspace/chat creation, bounded reads, sends, cancellation and configuration.
- Shared remote chats remain distinct from Mochi's conversation/current draft.
  Remote tasks survive a Mochi turn and are not silently resubmitted on failure.
- Human CLI/App input takes precedence: conflicting automatic follow-ups stop.
  Permission escalation requires AgentLink native confirmation, never auto-approval.
- AgentLink credentials stay in AgentLink and are excluded from export/share.
  Subagents, schedules and network/browser workarounds do not gain these Tools.

### 3.12 Task center and configuration checks

The app header exposes Tasks; Settings also exposes Configuration check.
The task center unifies presentation, not execution ownership:

Its status-first dashboard uses one filtered list and focused task details,
not separate execution consoles. Active and attention-needed work comes first;
overview counters also act as filters. Configuration checks have a persistent
header entry and a separate page. Common task actions need at most a card tap
and one explicit action; schedule Run now and future-run pause/resume are
available directly in its details.

- Foreground and Subagent runs show runtime status, current Tool, timestamp
  and cancellation. Stopping a child stops its owning parent interaction.
  The process-local view retains up to 100 finished runs plus active runs.
  Results remain in the conversation; it is not a new persistent chat archive.
- All persisted schedules appear regardless of their next run date. Actual
  running/queued state comes from WorkManager, separately from the next alarm
  and persisted last result. Stopping a run does not disable future recurrence.
  Cancellation records `CANCELLED` and advances an already claimed recurrence.
- Termux shows retained task status, bounded output, Stop and Forget for
  completed tasks. It replaces the separate task dialog. Opening the task
  center never submits a new Shell command. Detached processes retain the
  existing stop limitations.
- AgentLink exposes remembered chats and bounded task snapshots through
  guarded chat reads. Unknown remote outcomes/states are not success. Reads
  require its provider/chat Tool and current authorization; results and
  cancellation open the trusted native chat. No automatic resend is offered.
- While the task page is resumed, local schedule and Termux snapshots refresh
  every five seconds without overlapping refreshes. AgentLink reads occur
  only on opening/manual refresh. Closing the page stops its refresh work,
  not execution or previously submitted side effects.

Configuration checks are explicit and cancellable. They use saved settings
without modifying switches, credentials or permissions. The disclosure states
that one fixed short request goes to the configured AI Provider and may consume
quota; no conversation history, microphone audio or generated command is sent.
Each probe has a 15-second bound and failures do not hide independent results.

Results distinguish Passed, Needs attention, Not tested and Not enabled.
Model success proves only a basic text call, not Tool/image compatibility.
Speech checks cover permission, configuration and system-service readiness,
not actual recognition or cloud quotas; microphone testing and voice preview
remain explicit actions. Extension checks distinguish installation, trust,
connection and enablement; Termux repair opens its existing per-step setup.
Skill checks list missing Tool groups and respect individual switches.
Connection summaries are not proof of a successful device or Shell action.
Remediation opens the relevant Settings, Tools, Skills or trusted setup flow.
Raw Provider errors and secrets never enter the report, logs or exports.

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
- Downloaded executable code, unsigned extension packages, or dynamically
  loaded DEX/JAR plugins.
- Generic camera streaming or body-composition history in the first Mi Home
  extension release.

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
- Optional extensions remain absent from the Agent registry until the package,
  signature, protocol version, connection, provider switch, and individual
  Tool selection all validate.
- A successful latest-camera-event image request renders a trusted local image
  card that adapts to portrait and landscape viewports, keeps the photo primary,
  overlays event context, and states that the source is not live. With explicit
  provider permission, the same bounded image is attached once to the current
  foreground model run and may be relayed to at most one explicitly requested
  serial Subagent, but never persisted to history, memory, logs, export, or
  scheduled runs.
