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
- enabled MCP tools discovered through the Tool catalog;
- enabled custom REST tools in foreground Main Agent conversations only.

### Custom REST tools

`core/rest` owns serializable definitions, validation, schemas, scalar response
selection, and a cancellable HTTP adapter. `ToolCatalogRepository` owns encrypted
credential persistence and live revision/enablement checks. A stable `rest_`
alias derives from connection and endpoint IDs, not user names or secrets.
Schemas include parameter types/defaults, selected output meanings/units, and
`confirmed=true` when required. This is the existing Agent confirmation
contract, not proof of a native per-call approval dialog. All writes require it;
GET defaults to requiring it and can explicitly opt out for trusted reads.

Supported authentication: none, Bearer Token, dedicated API-key header, or a
complete Authorization header value. Secrets are attached only by native HTTP;
they are never schema fields, returned summaries, shared-provider exports, or
model arguments. A blank replacement preserves the saved Token only for the
same origin and authentication/header. New origins or authentication require
a replacement. Test and runtime responses redact known Token echoes before
field discovery/selection. Other returned data remains untrusted external
evidence, not instructions, and selected fields may enter model context/history.

Limits: 20 connections, 20 tools per connection, 20 scalar parameters, 32 output
fields. Origins must be public HTTPS:443 with no path, credentials, query, or
fragment. Actual DNS/connected addresses must be public; proxies, redirects,
and automatic request redispatch are disabled. Paths stay on the fixed origin;
query/path values are encoded and body values retain validated JSON types.
Methods are GET/POST/PUT/PATCH/DELETE; GET cannot carry a body. Only scalar string,
finite-number and boolean inputs/outputs are supported. Fixed JSON bodies are
limited to 16,000 characters; responses to 256 KiB after decompression and 64
nesting levels; selected output to 32 KiB UTF-8. Discovery shows at most 128 scalar
fields, eight levels deep and twenty items per array; a JSON pointer array
index selects one item, not a dynamic list mapping.

HTTP errors, missing/type-changed fields, and mismatched optional business-success
pointer/value return typed failures. Without that condition, success means only
HTTP success and valid selected fields, not verified business completion.
Calls have a 20-second deadline (10-second connect, 15-second read). Cancellation
cancels the HTTP call; timeout/cancellation cannot prove a mutation did not run.
Never automatically repeat an unknown-outcome write. Every live call checks
saved revision and both switches before dispatch and after response; revoked
results are discarded, but already-submitted operations are not undone.

The editor's test button has a separate native real-request confirmation and
does not call the model. REST is intentionally absent from scheduled/child
registries and Provider sharing. Python, OAuth, cURL/OpenAPI import, arbitrary
transforms, streaming/binary responses, and local-network access are not part
of this initial connector.

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
allowlists for built-in Notion, Tencent Docs, and Feishu providers. Remote
`readOnlyHint` annotations and manually configured MCP servers do not grant
Subagent access.
Both child roles can additionally receive `termux_exec` and `termux_task`
when the Termux provider is connected and enabled and the individual Tools
are enabled. No other extension or companion provider gains child access.

### Document-provider defaults

Connecting Notion, Tencent Docs, or Feishu enables the discovered Tools in the exact
allowlists below. These defaults cover document/spreadsheet operations, not
sharing, permission changes, membership, comments, or remote Agent execution.
Unrecognized Tools remain off; never enable a name merely because it starts
with a provider prefix or has a remote read-only annotation. Availability and
supported operations always come from that connection's actual MCP schemas.

Feishu defaults are exactly `search-doc`, `fetch-doc`, `create-doc`,
`update-doc`, and `list-docs`. Discovery must return all five before connection
succeeds; other remote names are excluded. Only search/fetch/list enter
read-only Subagent registries. The optional Feishu Knowledge Skill requires
all five, starts disabled, and teaches search pagination, wiki browsing,
read-before-write, ambiguity resolution, and post-write readback. It must
disclose that search covers doc/docx only, wiki listing is node-scoped, embedded
sheet/Bitable content is unsupported, and whole-document deletion, spreadsheets,
Bitable, PPT, comments, downloads, and permissions are not exposed.

Notion defaults:

| Purpose | Remote Tool names |
| --- | --- |
| Find, read, check access | `notion-search`, `notion-fetch`, `notion-get-tool-access` |
| Create/edit pages | `notion-create-pages`, `notion-update-page` |
| Organize/copy | `notion-create-folder`, `notion-move-pages`, `notion-duplicate-page` |
| Databases and views | `notion-create-database`, `notion-update-data-source`, `notion-query-data-sources`, `notion-create-view`, `notion-update-view` |
| Confirm async writes | `notion-get-async-task` |

The [official Notion MCP tool list](https://developers.notion.com/guides/mcp/mcp-supported-tools)
does not advertise a standalone whole-page deletion Tool. Do not invent one or
claim that page updates can trash/archive a page unless the discovered schema
explicitly supports it. Removing content is not the same as deleting its page.
For asynchronous writes, consume the returned task status with its backoff
instead of repeating the write or treating submission as completion.

Tencent Docs has 51 selected candidate names: the following 44 default-enabled
document Tools and seven optional Tools. Only actual discovered schemas appear.

| Purpose | Remote Tool names |
| --- | --- |
| Find/read | `query_space_node`, `search_space_file`, `manage.search_file`, `get_content`, `manage.query_folder_meta` |
| Create/delete files | `manage.create_file`, `create_space_node`, `delete_space_node` |
| Create document formats | `create_smartcanvas_by_markdown`, `create_word_by_markdown`, `create_excel_by_markdown`, `create_slide_by_markdown`, `create_mind_by_markdown`, `create_flowchart_by_mermaid` |
| Smart Canvas structure | `smartcanvas.get_top_level_pages`, `smartcanvas.get_page_info`, `smartcanvas.get_element_info`, `smartcanvas.find` |
| Smart Canvas edits | `smartcanvas.create_smartcanvas_element`, `smartcanvas.append_insert_smartcanvas_by_markdown`, `smartcanvas.update_element`, `smartcanvas.delete_element` |
| Document structure | `doc.resolve_document_structure`, `doc.get_last_operable_pos`, `doc.get_images` |
| Document edits | `doc.insert_paragraph_with_text`, `doc.find_and_replace`, `doc.insert_image`, `doc.insert_code_block` |
| Spreadsheets | `sheet.get_sheet_info`, `sheet.get_cell_data`, `sheet.operation_sheet`, `batch_update_sheet_range` |
| SmartSheet structure | `smartsheet.list_tables`, `smartsheet.list_fields`, `smartsheet.add_fields`, `smartsheet.add_view` |
| SmartSheet records | `smartsheet.list_records`, `smartsheet.add_records`, `smartsheet.update_records`, `smartsheet.delete_records` |
| Slides | `slide_get_page_info`, `slide_find_text`, `slide_append_text` |

The seven default-off candidates are `manage.export_file`, `manage.get_privilege`,
`manage.set_privilege`, `doc.get_comments`, `doc.compare_documents`,
`sheet.set_link`, and `sheet.set_freeze`. Selection is limited to these 51 names,
not every Tool advertised by Tencent Docs. The default-enabled candidates sort
first; duplicates and unknown names do not consume additional slots.
File/element deletion requires explicit user intent and exact targets.
Recursive `delete_space_node(remove_type=all)` requires explicit recursive
intent; a request to delete one document must not expand to its descendants.

Default policy version 3 resets existing connected built-in providers' child
switches once, preserving credentials and master switches. Tencent catalog
selection version 1 rediscovers enabled old connections once to replace the
previously truncated catalog with the available subset of these 51 candidates.
Disabled connections wait until enabled. Failed discovery keeps cached Tools,
shows a localized Tools notice, and can be retried by reopening Tools.
Cancellation propagates; stale discovery cannot restore a disconnected or
reconfigured connection. This migration never reads or writes user documents.
Subsequent individual switch changes persist across reloads and master toggles.
Explicit shared-provider imports retain their selected switches. These expanded
defaults do not expand the read-only Subagent allowlists or enable any Skill.

The Tencent Docs Skill begins with global search and directory browsing, including
scope discovery, pagination, deduplication and honest partial-coverage reporting.
Its search requirement accepts either enabled `manage.search_file` or
`search_space_file`; discovery, UI readiness and Skill loading use the same rule
without inventing an executable alias. Browsing and content-read Tools remain
required. Each document type has separate create/read/update/delete guidance:
Smart Canvas resolves page/element IDs; Word uses document structure; ordinary
spreadsheets resolve sheets/ranges; SmartSheet resolves field/record IDs; PPT
distinguishes creation/append from unsupported arbitrary edits. Whole-file
deletion is separate from deleting content, records or worksheets. Writes use
targeted readback where possible and never replay solely to check their outcome.
The Skill cannot grant unavailable APIs or claim full global search when the
connection cannot discover/access all requested scopes.

## 1.1 AgentLink companion provider

Exactly three application-defined schemas are registered:

| Tool | Actions |
| --- | --- |
| `agentlink_workspace` | `list`, `create` |
| `agentlink_chat` | `list`, `create`, `read`, `open` |
| `agentlink_control` | `send`, `cancel`, `configure` |

Workspace list without `machineId` discovers the authorized machines; with
`machineId` it lists Bridge workspaces. Creation is permission-scoped and must
not bypass native AgentLink confirmation for directory/register/worktree/clone
permissions. Ground `machineId`, `workspaceId`, `chatId`, and `agentId` from
successful discovery. Chat reads use `afterEventId` and `limit` (1–100), returning
the shared authoritative `chat`, bounded events and cursor, task/config/approval
state and freshness. Messages distinguish human/CLI/Mochi provenance.

`send` takes `content`; `cancel` takes a `taskId` returned by the latest chat
read and reports the actual queued/active cancellation outcome. Chat lists accept
`offset`/`limit`; follow `nextOffset` while `hasMore`. Clone uses `url`, translated
to the Bridge's `repositoryUrl`. `configure` requires native human confirmation.
`operationId`, `source=mochi` and
`expectedHumanRevision` are injected for sends by the client executor, never model fields.
Cancellation maps the observed task ID to the existing operation, not a new ID.
Read must return the requested `chat.chatId` and numeric `chat.humanRevision`
before control becomes eligible. A successful write consumes that read guard;
CONFLICT, timeout or uncertain write blocks same-run follow-ups even after
another read. Never automatically resend or approve permission escalation.

`open` after a successful read offers a native linked-chat action in Tools;
it does not accept or execute a URI, Intent or component from the model.
Linked task IDs and cursors are not cached-live state. Remote tasks survive
Mochi cancellation; explicit `cancel` is the only task-cancellation request.

The application, not a model Tool or scheduled Agent, follows newly submitted
operations with the additive read-only `agentlink_chat` IPC action `task`
(`task.read` in the Bridge). It requires exact machine/chat/task identity,
Mochi provenance and a bounded typed receipt. This action is not added to the
model schema. Durable per-task revisions make collection idempotent without
depending on retained event pages. The monitor never sends, approves, cancels or
restores a run-local human-revision write guard. Older companions/bridges expose
an explicit update-needed state; there is no browser/network fallback.

Both provider and individual switches are required, along with fresh connection
and authorization. Disabled/unavailable definitions and the dependent Skill are
excluded from discovery. AgentLink is unavailable to Subagents and Scheduled
Agents. Mid-run revocation returns typed `PERMISSION_DENIED` with a
Tools > AgentLink connection instruction. Browser, HTTP, JavaScript and MCP
must never be used as an authorization or disabled-Tool workaround.

## 2. Planner tools

### Optional Termux tools

`termux_exec(command, workdir?, timeout_seconds?)` submits unrestricted shell
when connected and enabled, returning `task_id` and `state=submitted`.
It does not claim completion. `workdir` defaults to Termux HOME, commands are
bounded to 16 KiB UTF-8, and timeout is 1–1800 seconds, default 120.

`termux_task(action, task_id?)` supports `list`, `read`, `stop`, and `forget`.
List returns only owned task IDs; other actions require one of those IDs.
Read/stop return state, optional exit code, bounded stdout/stderr and a
truncation flag. A nonzero command exit is a successfully observed `failed`
task, not a successful command; dispatch/authorization/transport failures use
the standard error envelope. Forget removes only completed task output.

Both provider and individual switches are enforced on every call. Foreground
Main Agents, Scheduled Agents, and both Subagent roles execute enabled tools
automatically, without per-command/run confirmation or a separate background
setting. Disabling a Tool/provider or disconnecting invalidates old registries,
including after re-enabling. The default-off Termux Skill follows the actual
registry's required-Tool readiness. There is no model-supplied shell `confirmed`
flag, silent retry, or unrestricted Android Intent dispatch.
See `EXTENSIONS.md` for setup, command privacy and process-lifetime guarantees.

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

Document-provider defaults are defined in section 1 above. Tencent Docs Tool
descriptions are normalized to bounded English labels locally, including for
previously discovered definitions. Up to 256 remote Tools can be discovered;
Tencent retains the 51 explicitly selected candidates, with 44 default-on and
seven optional. Versioned rediscovery and default migration follow section 1.
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

### Feishu user authorization

The dedicated provider uses `https://mcp.feishu.cn/mcp`, never a personal
seven-day MCP URL or the local Node/OpenAPI server. Users supply their own
enterprise self-built application's App ID and App Secret through Tools.
Ordinary Feishu applications are confidential clients: PKCE does not remove
the App Secret requirement. No common secret or public callback backend is
embedded in Mochi.

Authorization uses the system browser and official
`https://accounts.feishu.cn/open-apis/authen/v1/authorize`, random state,
S256 PKCE, and the exact allowlisted redirect
`http://127.0.0.1:43827/oauth/feishu`. A temporary IPv4 loopback-only listener
binds before launching the browser. It checks method, path, Host and a single
matching state, bounds HTTP input, rejects unrelated requests without consuming
the attempt, and never reflects codes into its response. The listener closes
on callback, rejection, cancellation, error, or the five-minute authorization
deadline. Port conflicts are explicit errors; no LAN/wildcard listener or
public network-policy exception is introduced. The browser must run on the
same phone. Process death requires a new authorization attempt.

Code exchange and refresh use the fixed
`https://accounts.feishu.cn/oauth/v3/token`, form encoding, a 25-second request
deadline, cancellable body reads, no redirects, and no automatic retries.
HTTP-200 nonzero business codes, missing tokens, invalid lifetimes, and
insufficient actual granted scopes are failures. Error feedback never reflects
raw provider descriptions or credentials.

The guided five-tool permission set is `offline_access`, `search:docs:read`,
`wiki:wiki:readonly`, `docx:document:readonly`, `task:task:read`, `im:chat:read`,
`docx:document:create`, `wiki:node:read`, `wiki:node:create`,
`docs:document.media:upload`, `board:whiteboard:node:create`, and
`docx:document:write_only`, all with user identity. The official service
requires every listed permission per Tool, including its non-document-looking
dependencies. These do not add task/chat/member Tools. The user must publish
permission changes and have application availability. See the
[official remote service contract](https://open.feishu.cn/document/mcp_open_tools/developers-call-remote-mcp-server)
and [OAuth refresh contract](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/authentication-management/access-token/refresh-user-access-token-v3).

App secrets and both tokens are Keystore-encrypted in the Tool catalog, never
in Skills, prompts, logs, backups, or Provider shares. Expiry uses the returned
lifetimes. Refresh is serialized and persists the rotated token pair atomically.
The single-use refresh token is removed from storage before transmission;
ambiguous network results, cancellation, or process death cannot replay it and
require reconnect. Connection revisions prevent disconnect/reconnect from
being overwritten by late authorization or refresh. Separate registry revisions
invalidate captured tools after switches change without discarding refreshed
credentials. Disconnect removes local credentials, not Feishu-side consent;
the user can revoke that in Feishu.

Feishu transport sends `X-Lark-MCP-UAT` and `X-Lark-MCP-Allowed-Tools`, never
Bearer/TAT mode. Discovery requests only the five candidates; each execution
allows only its currently enabled target. These headers are restricted to the
fixed Feishu provider and endpoint. Requests have a 30-second deadline,
cancellable response-body reads, no redirects and no automatic retry.
Schemas use `feishu_` aliases, while calls retain the discovered remote name.
Top-level JSON-RPC errors and `isError=true` results are failures, not writes
to be retried automatically.

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
