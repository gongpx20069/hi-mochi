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
- `get_current_weather`;
- `navigate_mochi_ui`;
- `run_sandboxed_javascript`;
- the five grouped Agent Browser Tools;
- configured Baidu Map Agent Plan Tools;
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

The final Agent payload may also contain a `card_directive`. It is not a Tool:
the Orchestrator binds it only to successful Tool evidence from the same run,
then Android resolves Home, inline, or deferred placement and renders a trusted
Compose card. Typed weather/calendar/todo cards remain deterministic; external
web and MCP evidence can use a bounded general content card selected by the
model. See `CARD_PRESENTATION.md`.

### Baidu Map Agent Plan

The built-in Baidu Map provider stores a user-owned Service Key encrypted with
Android Keystore. Its provider switch and five individual Tool switches must
both be enabled before the Tools enter the Agent prompt:

- `baidu_map_place`
- `baidu_map_direction`
- `baidu_map_geocoding`
- `baidu_map_reverse_geocoding`
- `baidu_map_weather`

Requests use the official `https://api.map.baidu.com/agent_plan/v1/` HTTPS
endpoints with `Authorization: ******. Responses are bounded and enter
the same-run general content Card evidence path. Coordinates must be trusted
GCJ-02 values rather than model-generated guesses.

### `get_current_weather`

Returns current local conditions from Open-Meteo using the device's
permission-gated location:

- weather condition;
- temperature in Celsius;
- apparent temperature in Celsius;
- relative humidity;
- observation time and timezone.

Coordinates are reduced to two decimal places before they leave the device.

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
is read-only and disabled by default.

The built-in Dianping provider is a Mochi-owned in-process MCP adapter over
the official Dianping POI Open Platform. It exposes only
`dianping_search_poi` and `dianping_get_poi`; it does not scrape Dianping,
import cookies, create orders, reserve, queue, call, or pay. AppKey, AppSecret,
search session, and optional per-interface detail session are encrypted with
Android Keystore. Requests use the documented lowercase/sorted MD5 signature,
bounded official JSON responses, and only provider-returned H5 or app links.
Actual cities, categories, reviews, prices, and link fields depend on the
partner permissions attached to the credentials. Because AppSecret is a
partner shared secret, production deployments should prefer a trusted relay
when the partnership permits one.

FlyAI's public CLI calls `https://flyai.open.fliggy.com/mcp` with a stateless
`tools/call` request plus proprietary `x-ff-ctx`, timestamp, nonce, HMAC, and
client headers. Mochi must not copy the trial authorization or signing material
embedded in the published npm bundle. Direct Android support therefore remains
blocked until FlyAI issues Android-approved signing material and client
identity rules, or an approved relay is selected.

## 7. Previously proposed tool groups

- `trigger_haptic`
- `show_notification`
- `load_skill`
- `persona`
- `http_fetch`
- `script_execute`

## 8. Opt-in Android tools

- `manage_alarm`
- `manage_timer`
- `manage_contact`
- `open_url`
- `open_maps`
- `share_text`

Calls and SMS open system UI; Mochi does not silently place calls or send
messages. Contact mutation requires explicit permission and confirmation.

## 9. Agent loop

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
