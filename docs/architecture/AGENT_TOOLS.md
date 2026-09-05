# Native Agent Tool Contracts

Kotlin schemas and typed executors under
`android/app/src/main/java/com/example/mochi_pet/core/agent/tool/` are the
authoritative implementation.

## 1. Common contract

Every tool returns one typed JSON envelope:

```json
{"status":"ok","data":{}}
```

or:

```json
{"status":"error","code":"INVALID_ARGS","message":"..."}
```

Required error codes include `INVALID_ARGS`, `NOT_FOUND`, `CONFLICT`,
`PERMISSION_DENIED`, `CANCELLED`, `PROVIDER_ERROR`, `TIMEOUT`, and
`INTERNAL_ERROR`.

Tool arguments are untrusted. Executors validate enums, lengths, timestamps,
IDs, permissions, and state before performing side effects.

The native `ToolRegistry` currently supports:

- `manage_mochi_calendar`;
- `manage_mochi_todo`;
- `manage_mochi_schedule`;
- `get_current_location`;
- `get_current_weather`;
- `navigate_mochi_ui`;
- `run_sandboxed_javascript`;
- `delegate_agent` in the Main Agent's request-scoped registry;
- the five grouped Agent Browser Tools;
- configured Amap map and merchant Tools;
- enabled MCP tools discovered through the Tool catalog.

Agent Browser provides five grouped schemas:

- `browser_read`;
- `browser_navigate`;
- `browser_click`;
- `browser_input`;
- `browser_scroll`.

They operate one visible, per-turn Android WebView and return bounded
agent-only snapshots containing viewport Markdown plus a separate interactive
element list with temporary references. Every successful action returns a fresh
snapshot. Browser details and lifecycle are defined in `AGENT_BROWSER.md`.

### `delegate_agent`

Arguments:

- `agent`: `researcher` or `analyst`;
- `task`: a self-contained task, limited to 12,000 characters.

The Tool runs one child synchronously and returns:

```json
{"status":"ok","data":{"agent":"researcher","result":"..."}}
```

The request-scoped coordinator permits at most two delegations. Child
registries never contain `delegate_agent`. Researcher may use enabled Browser
Tools, read-only MCP Tools, and `load_skill`; Analyst adds
`run_sandboxed_javascript`. Read-only MCP access uses application-controlled
allowlists for built-in Notion and Tencent Docs providers. Remote
`readOnlyHint` annotations and manually configured MCP servers do not grant
Subagent access.

## 2. Planner tools

### `manage_mochi_calendar`

Operations: `create`, `list`, `update`, `delete`.

Calendar events live in Mochi Room storage. IDs are Mochi IDs; no Android
calendar ID is required.

Important fields:

- `event_id`
- `title`
- `description`
- `start_iso`
- `end_iso`
- `all_day`
- `timezone`
- `recurrence_rule`
- `location`
- `reminder_iso`
- `range_start_iso` / `range_end_iso`

Delete requires explicit user intent. System calendar import/export is a
separate optional adapter and is never implicit.

The native executor requires `confirmed=true` for delete.

### `manage_mochi_todo`

Operations: `create`, `list`, `update`, `complete`, `delete`.

Important fields:

- `todo_id`
- `content`
- `status`
- `priority`
- `scheduled_date`
- `due_iso`
- `reminder_iso`
- list filters for date and status

The agent must not infer a todo from ordinary conversation.

The native executor requires `confirmed=true` for delete and requires
`operate=complete` instead of changing completion state through update.

## 3. Presentation tool

### `navigate_mochi_ui`

Operations:

- `show_face`
- `show_date_time`
- `show_weather`
- `show_conversation`
- `show_today`
- `show_calendar_month`
- `show_calendar_day`
- `show_todo`

Optional arguments:

- `date`: ISO local date
- `month`: `YYYY-MM`
- `section`: `time`, `date`, `weather`, `agenda`, `events`, or `todos`
- `status`: todo filter
- `highlight_ids`: locally existing item IDs

This tool does not mutate planner data. The executor applies
`NavigationPolicy`; invalid or context-inappropriate navigation is rejected.

Calls include a required semantic `reason`. Generic calendar/time knowledge is
rejected, current time/date prefers the Home date-time presentation, current
weather prefers the Home weather presentation, today's planner context prefers
Today, and non-today date context prefers Calendar Day.

The final response contract requires current local time/date requests to return
`surface=date_time` with `reason=current_time_date`. Current local weather,
temperature, or humidity requests must call `get_current_weather` and return
`surface=weather` with `reason=current_weather`. These deterministic Home
presentations use `ui_directive`, never a generic `card_directive`.

The final Agent payload may also contain a `card_directive`. It is not a Tool:
the Orchestrator binds it only to successful Tool evidence from the same run,
then Android resolves Home, inline, or deferred placement and renders a trusted
Compose card. Typed weather/calendar/todo cards remain deterministic; external
web and MCP evidence can use a bounded general content card selected by the
model. See `CARD_PRESENTATION.md`.

### Amap Maps provider

The built-in Amap provider stores a user-owned Web Service Key and optional
Security Key encrypted with Android Keystore. Its provider switch and six
individual Tool switches must both be enabled before the Tools enter the Agent
prompt:

- `amap_search_poi`
- `amap_get_poi`
- `amap_direction`
- `amap_geocoding`
- `amap_reverse_geocoding`
- `amap_weather`

Requests use fixed official `https://restapi.amap.com/` HTTPS endpoints. When
the user supplies a Security Key, Mochi adds the documented request signature.
POI search and detail requests always ask for `business` and `photos`, allowing
supported categories to return ratings, average cost, hours, phone, tags, and
photos. Missing fields remain missing; the Agent must not infer review text,
ratings, prices, or open state. Responses are bounded and enter the same-run
general content Card evidence path. Coordinates must be trusted GCJ-02 values
rather than model-generated guesses.

The built-in Travel Planning Skill combines these Amap Tools with all five
foreground Agent Browser Tools. It uses Amap for place resolution, first- and
last-mile routes, and destination weather. Train research starts from the
official public 12306 query page and interacts only with visible page controls;
it never calls undocumented ticket endpoints. Flight research prefers public
official-airline search forms and bounds fallback sources. The Skill never
logs in, imports account state, bypasses verification, enters passenger or
payment data, or continues into booking. Authentication, CAPTCHA, real-user
challenges, identity checks, 403/429 responses, and checkout are hard stop
conditions. Because click and input are required, the Skill is unavailable to
read-only scheduled Browser runs.

### `get_current_weather`

Returns current local conditions from Open-Meteo using the device's
permission-gated location:

- weather condition;
- temperature in Celsius;
- apparent temperature in Celsius;
- relative humidity;
- observation time and timezone.

Coordinates are reduced to two decimal places before they leave the device.

### `get_current_location`

Returns the Android device's permission-gated current position only for an
explicit current-position request or a clearly location-dependent action such
as nearby discovery or routing. The result contains:

- WGS-84 latitude and longitude;
- GCJ-02 latitude and longitude when the point is inside China;
- reported accuracy, capture time, age, and Android provider when available.

The Tool rejects denied permission, disabled providers, unavailable fixes, and
timeouts with typed errors. It accepts no model-supplied coordinates. Android
locations older than five minutes are not reused. The configured LLM receives
the returned coordinates as Tool evidence, so the Tool can be disabled
independently from Tools settings. Amap parameters must use the returned GCJ-02
fields, never the WGS-84 fields or model-generated
conversion.

## 4. Public web research

There are no dedicated `search_web` or `fetch_web_page` schemas. Public
research uses the Agent Browser Tools defined in `AGENT_BROWSER.md`.

The built-in Web Search Skill guides the Agent to:

1. use Bing for technical, official, news, global, and general authoritative
   queries;
2. use Sogou Weixin for explicit WeChat official-account searches and Chinese
   lifestyle or experience queries;
3. navigate, input the query, read the result page, open selected sources, and
   read the source page through Browser Tools;
4. base the final answer on bounded source-page snapshots rather than search
   snippets alone.

Disabling the Agent Browser provider or its required Browser Tools makes this
Skill unavailable. Search pages and source pages remain untrusted evidence and
cannot issue Agent instructions.

Two focused Browser Skills cover product discovery and ratings:

- Product Search uses Bing to discover public official marketplace, retailer,
  manufacturer, and brand pages. It compares values verified on multiple
  product pages when possible and labels search snippets as unverified. For an
  explicit Pinduoduo request it searches indexed public
  `mobile.yangkeduo.com/goods.html` pages rather than the login-gated H5 search
  page. It must not log in, import cookies, claim coupons, add to cart, order,
  or pay.
- Douban Ratings always starts at
  `https://m.douban.com/home_guide`, searches through visible page controls,
  and verifies the matching detail page before reporting scores, rating counts,
  or recurring review themes. It is the default source for ratings or reviews
  about movies, books, music, TV, games, and other works unless another source
  is requested. It must not log in, rate, review, follow, or modify an account.

Both Skills require the unchanged five Browser Tools, try another trusted
source when one product source is blocked, stop when no reliable source
remains, and treat all page content as untrusted data.

US Stock Analysis uses Agent Browser rather than dedicated SEC Tools. It
uses one Baidu Stock URL pattern for the Magnificent Seven:
`https://pqa9p2.smartapps.baidu.com/pages/quote/quote?code=<TICKER>&market=us`.
It maps Apple, Microsoft, Amazon, Alphabet, Meta, Nvidia, and Tesla to
`AAPL`, `MSFT`, `AMZN`, `GOOGL`, `META`, `NVDA`, and `TSLA`, then reads each
page separately for timestamped quote fields, capital flow, news, technical
support/resistance, institutional ratings and targets, financial summaries,
and company details. Comparisons use the same market session and closest
practical retrieval time.

Official investor-relations pages remain primary evidence for company-reported
earnings and guidance. Baidu technical levels and institutional consensus are
provider-calculated secondary evidence. The page's `股评` content is kept in a
separate low-confidence crowd-commentary section with period, sample size,
source, author, and post time when visible; anonymous trading instructions and
leverage claims are never treated as facts. The Skill separates facts,
provider indicators, analyst opinion, crowd sentiment, calculations,
catalysts, and risks and cannot trade or access brokerage accounts.

Scheduled Agent runs may use the read-only Browser subset
(`browser_navigate`, `browser_read`, and `browser_scroll`). Browser turns are
serialized so a background schedule cannot replace an active foreground
WebView session. Background runs cannot click controls or enter page data.

## Scheduled Agent automations

`manage_mochi_schedule` exposes four operations: `set`, `list`, `remove`, and
`run`. Schedules support one-time instants, daily or weekly local times, and
intervals of at least 15 minutes. They are persisted in Room and shown in
Planner with an Agent marker.

Android registers the next occurrence through AlarmManager. The alarm receiver
only enqueues unique WorkManager execution; a transactional due-time claim
prevents duplicate scheduled runs. Reboot, wall-clock, timezone, package, and
exact-alarm permission changes reconcile all active schedules.

The Worker uses the same Agent runner factory as foreground Conversation,
including the current provider, persona, memory, Skills, and enabled
background-safe Tools. It may use the serialized read-only Browser subset
(`browser_navigate`, `browser_read`, and `browser_scroll`), while click, input,
and visible navigation remain excluded. The tagged scheduled prompt and final
response are saved to Agent Memory, so they appear in Conversation with their
stored timestamps. Completion posts a notification and calculates the next
occurrence; one-time schedules disable after execution.

The final model response may instead include the same data as `ui_directive`.
The app uses one validator and one navigation path for both forms.

## 5. Sandboxed JavaScript

### `run_sandboxed_javascript`

Runs a short JavaScript function body in AndroidX JavaScriptEngine's isolated
WebView process. The function reads optional JSON through `input` and must
explicitly return a JSON-compatible value.

The runtime exposes no network, files, packages, Android APIs, native Tools, or
MCP bridge. Each call uses a fresh isolate with a one-second timeout, 16 MiB
heap limit, and 64 KiB return limit. Syntax/runtime failures and timeouts return
typed Tool errors instead of aborting the Agent loop.

## 6. MCP tools and Tool catalog

The top-level Tools surface controls which schemas enter the model request.
Built-in definitions are immutable but can be enabled or disabled. Disabled
Tools are absent from `OpenAiChatRequest.tools`.

Agent Browser appears as one provider card with one provider-level switch and
an adjacent expandable list of its five individual Tool switches. The Browser
Tools must not be scattered across the general built-in list.

Mochi supports public HTTPS Streamable HTTP MCP servers. It initializes a
session, sends `notifications/initialized`, discovers `tools/list`, and invokes
selected tools with `tools/call`. Manual servers can use no authentication or
an encrypted Bearer token. Each discovered Tool is independently selectable.

MCP aliases are deterministic. Notion names use underscore-normalized aliases
such as `notion_search`; manual servers use
`mcp_<server>_<remote_tool>`. Responses, pagination, names, descriptions, and
tool counts are bounded.

Notion and Tencent Docs enable their core discovery and reading Tools on first
connection. Tencent Docs Tool descriptions are normalized to bounded English
labels locally, including for previously discovered definitions. Up to 256
remote Tools can be cataloged; older Tencent Docs catalogs truncated at 64
Tools are rediscovered automatically while retaining enabled selections. The
Tencent Docs UI and Agent registry retain only the 32 highest-priority Tools,
with workspace listing, search, and content reading always prioritized.
Current Tencent Docs deployments expose workspace search as
`manage.search_file`; the older `search_space_file` name remains supported.

The built-in Notion provider uses `https://mcp.notion.com/mcp`, OAuth
Authorization Code with PKCE, dynamic client registration, encrypted rotating
tokens, and the `mochi://oauth/notion` callback. Notion schemas are excluded
until authorization succeeds, the server is enabled, and at least one
discovered Tool is selected. The separate read-only Notion Knowledge Skill is
disabled by default and enters the prompt only when the user enables it.

The built-in Tencent Docs provider uses the official hosted endpoint
`https://docs.qq.com/openapi/mcp`. The user obtains a personal MCP token from
`https://docs.qq.com/open/auth/mcp.html`; Mochi stores it with Android Keystore
encryption and sends it as the provider-required raw `Authorization` value.
Search, read, SmartCanvas creation, append, and update tools are selected by
default after successful discovery. The separate Tencent Docs Knowledge Skill
is read-only and disabled by default. Its readiness prerequisite is presented
as the single **Tencent Docs MCP** aggregate; the aggregate is unavailable when
the server or any Tencent Docs Tool required by the Skill is unavailable.

FlyAI's public CLI calls `https://flyai.open.fliggy.com/mcp` with a stateless
`tools/call` request plus proprietary `x-ff-ctx`, timestamp, nonce, HMAC, and
client headers. Mochi must not copy the trial authorization or signing material
embedded in the published npm bundle. Direct Android support therefore remains
blocked until FlyAI issues Android-approved signing material and client
identity rules, or an approved relay is selected.

## 7. Signed extension Tools

The Tools surface places signed Extensions after the MCP servers. Official
extension cards use the same provider-card hierarchy as built-in providers:
localized display name and description, stable monospace Tool ID, localized
risk label, and individual switch. The extension configuration Activity
receives Mochi's resolved Chinese or English language tag explicitly.

Extension Tool schemas enter the top-level registry only when all of these are
true:

- the expected APK is installed;
- its signing certificate and signature permission match Mochi;
- Binder protocol negotiation succeeds;
- the extension reports a connected state;
- the provider is enabled;
- the individual Tool is enabled.

The Mi Home aggregate provider remains disabled by default after connection.
Every child Tool definition defaults to enabled, so turning on the provider
activates the complete discovered Tool set; later individual Tool selections
remain explicit persisted user choices.

The initial Mi Home extension may expose:

- `mijia_list_devices`
- `mijia_get_device_state`
- `mijia_control_device`
- `mijia_control_television`
- `mijia_configure_camera`
- `mijia_get_latest_camera_event_image`
- `mijia_list_scenes`
- `mijia_run_scene`

All results use the common typed envelope. Extension errors are mapped to the
same validation, permission, not-found, provider, timeout, cancellation, and
internal error codes as native Tools. Tool names, descriptions, schemas,
argument sizes, result text, attachment count, and call duration are bounded
by the host.

`mijia_list_devices` returns selected supported devices grouped by home and
room, with stable device IDs, category, online state, model, and available
capability names. Duplicate names never remove their home/room qualifiers.

`mijia_get_device_state` reads only MIoT properties whose specification permits
reading. Common sensor results are restricted to selected temperature,
humidity, air-quality, contact, motion, and battery properties. The initial
scale result is limited to identity, connectivity, and battery state. It
excludes user profiles, weight, body-fat percentage, heart rate, body
composition, and measurement history.

`mijia_control_device` supports a semantic allowlist for common
specification-driven devices:

- light: power, brightness, and color temperature;
- switch or plug: power;
- fan: power, mode, and fan level;
- air conditioner: power, mode, target temperature, and fan level;
- air purifier or humidifier: power, mode, target value, and fan level;
- curtain: open, close, stop, and target position.

Each operation is offered only when the selected device specification declares
the required readable/writable property or action and the value passes the
declared range/enum contract. The Tool does not accept arbitrary MIoT service,
property, or action IDs.

`mijia_control_television` accepts only host-approved semantic operations that
map to writable properties or declared actions: power, input, volume, mute,
home, menu, settings, back, directional navigation, confirm, play, and pause.
The Tool reports command acceptance separately from a verified state change.
It never exposes a generic message-router action.

`mijia_configure_camera` accepts only named settings present in the selected
device specification. Camera power, recording, motion detection/tracking, or
other surveillance-affecting changes require explicit current-turn intent and
`confirmed=true`. Storage formatting/ejection, credentials, arbitrary action
IDs, stream-start actions, PTZ, playback, two-way audio, and live viewing are
not exposed.

`mijia_get_latest_camera_event_image` is foreground Main-Agent only. It
requires an explicitly selected camera and returns the newest available motion
or doorbell event metadata plus one JPEG/PNG attachment descriptor. It does not
claim the image is live, invoke a camera shutter, start a stream, or return
image bytes/URLs in JSON. Missing events or unsupported models return typed
errors. Successful evidence deterministically creates the trusted Camera
Snapshot card. If the provider's explicit image-input permission is enabled,
the host adds one normalized image to the same foreground model run without a
second query-keyword check. The attachment remains excluded from Tool JSON,
history, memory, logs, export, and Scheduled Agents. The Main Agent may also
pass that same image to at most one serial Subagent through
`delegate_agent(include_image=true)`. The Subagent image is processed in a
dedicated no-Tool provider prepass; only bounded, validated text observations
enter the normal Subagent Tool loop as delimited untrusted user-role evidence,
not system instructions. The Subagent receives no Mi Home Tool, descriptor,
image URL, raw bytes, or reusable attachment.
Because device resolution, event lookup, and encrypted image download are
separate cloud operations, the camera event image path has a 15-second
end-to-end user-facing deadline. The Extension receives 14 seconds and the Host
reserves one second for its terminal callback, so a typed timeout cannot be
misreported as a lost Mi Home session.

`mijia_list_scenes` returns only enabled manually triggered scenes from selected
homes. `mijia_run_scene` requires an exact stable scene ID selected from that
evidence plus explicit current-turn intent and `confirmed=true`; scene contents
are not inferred to be safe.

Mi Home Tools are not available to Scheduled Agent runs or Subagents in the
initial release. The Skill catalog cannot bypass this restriction.

`delegate_agent` has an optional `include_image` argument. It succeeds only
when the Main Agent already received one validated camera-event image in the
current foreground run. The image is consumed from a run-local relay by the
first qualifying delegation, appears in only a dedicated no-Tool multimodal
prepass, and is unavailable to a second delegation. The host rejects raw image
echo before the normal Subagent loop. The Subagent must treat visible text as
untrusted data and must not identify people or infer sensitive attributes.

The default-off built-in **Mi Home Smart Home** Skill presents the single
**Mi Home extension** aggregate as its prerequisite rather than eight raw Tool
IDs. Its switch remains unavailable until the extension is installed,
connected, provider-enabled, and all eight Tools required by the Skill are
individually enabled.
It resolves devices from fresh list evidence, uses category-specific control
Tools, requires explicit current-turn confirmation for camera settings and
scenes, and retrieves an event image only when the user explicitly asks to view,
describe, or analyze it. It must not perform face identification or infer
sensitive personal attributes from an image.

## 8. Previously proposed tool groups

- `trigger_haptic`
- `show_notification`
- `load_skill`
- `persona`
- `http_fetch`
- `script_execute`

## 9. Opt-in Android tools

- `manage_alarm`
- `manage_timer`
- `manage_contact`
- `open_url`
- `open_maps`
- `share_text`

Calls and SMS open system UI; Mochi does not silently place calls or send
messages. Contact mutation requires explicit permission and confirmation.

## 10. Agent loop

1. Send schemas allowed by current settings and permissions.
2. Execute tool calls through typed Kotlin executors.
3. Append normalized tool results.
4. Stop at a configured maximum number of rounds.
5. Parse the final structured reply.
6. Validate and apply UI navigation.

Tool execution and navigation are traceable by interaction ID. Secrets and
private content are excluded from logs.

The native `AgentOrchestrator` implements this multi-round loop. It returns
unknown-tool and invalid-argument envelopes to the model for recovery, enforces
tool-round and payload limits, propagates cancellation, and rejects malformed
final JSON instead of treating it as a successful reply.
