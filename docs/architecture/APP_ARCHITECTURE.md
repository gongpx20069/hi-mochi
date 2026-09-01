# Native Android App Architecture

## 1. Target layout

```text
android/
├── app/
├── core/
│   ├── agent/
│   ├── database/
│   ├── model/
│   ├── network/
│   └── platform/
├── feature/
│   ├── face/
│   ├── conversation/
│   ├── today/
│   ├── calendar/
│   ├── todo/
│   ├── tools/
│   ├── browser/
│   ├── settings/
│   └── voice/
└── build-logic/
```

Features depend on core interfaces. Core must never depend on feature modules.
Platform implementations sit behind interfaces so domain and policy logic can
run in local JVM tests.

## 2. Ownership

| Component | Owns |
| --- | --- |
| `VoiceSessionController` | interaction ID, cancellation, voice phases |
| `AgentOrchestrator` | prompt/model/tool loop and round limits |
| `SerialSubagentCoordinator` | one active child, per-run delegation count, cancellation propagation |
| `PromptBuilder` | persona files, memory recall, skill catalog, time context |
| `PersonaRepository` | app-private SOUL/USER/AGENTS files and atomic updates |
| `ConversationRepository` | persisted turns and SQLite memory retrieval |
| `SkillRepository` | Agent Skills metadata, resources, and enablement |
| `ToolRegistry` | schema registration, validation, dispatch |
| `ToolCatalogRepository` | built-in enablement, MCP servers, OAuth, selected schemas |
| `AgentBrowserSessionController` | visible per-turn WebView, snapshots, actions, and cleanup |
| `AgentExecutionService` | background Agent Browser lifetime and Stop notification |
| `McpStreamableHttpClient` | MCP sessions, discovery, and calls |
| `AndroidJavaScriptExecutor` | bounded JavaScript in an isolated process |
| `PlannerRepository` | calendar/todo transactions and queries |
| `SurfaceNavigator` | validated app-surface transitions |
| `NavigationPolicy` | intent/date-to-surface rules |
| `ReminderScheduler` | WorkManager/AlarmManager scheduling |
| Feature ViewModels | screen state and user events |

No Composable may call the LLM, database DAO, SpeechRecognizer, TextToSpeech,
or Android content provider directly.

## 3. State model

```kotlin
sealed interface MochiSurface {
    data object Face : MochiSurface
    data object Conversation : MochiSurface
    data object Skills : MochiSurface
    data object Tools : MochiSurface
    data object Today : MochiSurface
    data class CalendarMonth(val month: YearMonth) : MochiSurface
    data class CalendarDay(val date: LocalDate) : MochiSurface
    data class Todo(val date: LocalDate?, val status: TodoStatus?) : MochiSurface
}
```

The app has one authoritative navigation state. Gestures, buttons, reminders,
and voice directives all dispatch typed navigation intents to the same owner.
Agent Browser does not add a navigation surface: Home may render a temporary
Browser Card, while every other current surface remains unchanged.
Researcher and Analyst reuse that top-level Browser session; the runtime actor
label changes the Home card title without creating a second WebView.

## 4. Dependency rules

```text
Compose -> ViewModel -> Use case -> Repository interface
Platform adapter -> Repository/tool interface
Agent -> ToolRegistry -> typed tools
Main Agent -> SerialSubagentCoordinator -> isolated child AgentOrchestrator
Agent -X-> Compose/NavController/DAO/Android Context
```

Cross-module data uses immutable domain models. Do not pass Room entities,
Android `Context`, JSON maps, or navigation controllers through domain APIs.

## 5. Persistence

- Room transactions own planner consistency.
- DataStore owns non-relational preferences.
- Android Keystore protects BYOK credentials.
- A dedicated Tool DataStore owns built-in enablement, MCP configuration, and
  encrypted MCP/OAuth credentials.
- The same Tool DataStore stores the encrypted Amap Web Service Key, optional
  Security Key, and provider enablement. Six native Tools call fixed official
  HTTPS REST endpoints; they are not represented as a remote MCP server.
- Room stores editable market skills and their upstream/local digests; built-in
  skills remain read-only application resources while Room stores their local
  enable/disable overrides.
- Room stores successful conversation messages and a dedicated FTS4 index of
  normalized terms produced by the shared ICU-based tokenizer. Message and FTS
  rows are written in one transaction; schema migration rebuilds existing
  indexes with the current tokenizer. The repository ranks bounded FTS
  candidates and excludes the configurable recent window, selects up to four
  hits, includes three neighboring messages on each side, deduplicates, and
  restores chronological order. The Conversation UI renders each stored
  message timestamp in the device timezone beside its sender label.
- App-private `files/persona/SOUL.md`, `USER.md`, and `AGENTS.md` are seeded
  from bundled assets and updated atomically. Persona is intentionally not a
  Room model, and there is no HEARTBEAT file.
- Recent conversation context defaults to 20 user/assistant turns and is
  configurable from Settings. DataStore owns this preference.
- DataStore owns Focus standby enablement and its validated idle-delay choice.
  The default is enabled after 30 seconds; no Room migration is involved.
- Export/import uses a versioned schema and excludes secrets.

## 6. Error model

Errors are typed by domain: validation, permission, not found, conflict,
network, provider, cancellation, and internal. UI copy is derived at the
feature boundary. Do not turn failures into empty success responses.

## 7. Testing boundaries

- Pure JVM: date resolution, navigation policy, prompt assembly, tool argument
  validation, recurrence, and reducers.
- Room tests: DAO queries, transactions, migrations, export/import.
- Instrumentation: Compose navigation, permissions, reminders, process death.
- Real device: wake word, STT/TTS, audio focus, media buttons, OEM clocks.

## 8. Tool providers

The Agent registry is assembled immediately before each run so settings changes
take effect without restarting the app:

```text
immutable native definitions --enabled--> ToolRegistry
JavaScriptEngine adapter -----enabled--> ToolRegistry
Agent Browser provider -------enabled--> ToolRegistry
selected MCP definitions -----enabled--> ToolRegistry
Amap native Tools -----------enabled--> ToolRegistry
```

Skill discovery follows the same runtime enablement boundary. Only enabled
Skills enter the compact `<available_skills>` catalog. Full `SKILL.md` content
is loaded on demand, and activation must fail explicitly when the Skill is
disabled or when its required Tool/provider is unavailable. Tool parameters
remain in function schemas; the system prompt contains only compact global
Tool, navigation, and Card policy.

The Tools Compose surface never executes a Tool directly. It sends typed events
to `MochiHomeViewModel`, which updates `ToolCatalogRepository`. Notion OAuth
returns through the Activity deep-link channel and is completed by the
repository before discovered schemas become eligible for prompt registration.
Agent Browser enablement follows the provider-card pattern: one master switch
and five adjacent individual Tool switches. The session controller is created
lazily by the first Browser Tool call and destroyed after the final model
response, before TTS, or on every earlier exit path.
Tencent Docs uses its official hosted MCP endpoint with an encrypted personal
token pasted through the Tools surface; its discovered tools use a dedicated
raw-Authorization mode rather than OAuth or Bearer formatting.

Remote MCP endpoints pass the same public HTTPS policy as web tools. Private,
loopback, link-local, credential-bearing, and non-standard-port endpoints are
rejected. JavaScript execution is local but process-isolated and has no bridge
to Android or network capabilities.
