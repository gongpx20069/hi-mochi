# Tool discovery and disclosure

This is the single authority for **which Tools the model sees, how
`tool_search` loads them, and how the model/tool loop continues**.
[Tool contracts](AGENT_TOOLS.md) owns individual executor schemas, provider
allowlists, permissions and side effects. [Technical design](TECHNICAL.md)
owns Provider transport and Skill document format. Do not copy their inventories
or execution policies here.

## 1. Scope and ownership

The implementation uses an ordinary function named **`tool_search`** on both
Chat Completions and Responses. There is no new connection setting, model-name
heuristic, automatic protocol switch, or remote search service.

The complete, enabled, actor-scoped `ToolRegistry` remains local. A separate
run-owned disclosure state starts with the base set below and adds selected
definitions after search. Being registered, enabled, model-visible, and
authorized to execute are different states.

- `ToolCatalogRepository` owns configuration and live availability.
- `ToolRegistry` owns immutable definitions, argument dispatch and availability
  checks; executors retain their existing live revision/permission checks.
- `EnglishToolSchemas` owns prepared model-facing annotation copies and their
  source-versioned derived cache, never original UI metadata or execution.
- `AgentOrchestrator` owns one disclosure state per run, continuation history,
  cancellation and existing round limits.
- Search metadata/indexes are derived from that registry, never another
  actor's registry. They contain no credentials or user documents.
- `MochiSkill.resolveTools` owns shared relation resolution for Skill readiness,
  loaded `allowed-tools` headers and Skill-scoped search.
- Skill readiness uses the full authorized registry, not the disclosed subset.
  Reading a Skill never enables a Tool or automatically loads a whole provider.

No Room/DataStore migration, additional network dependency, or change to the
20-second foreground model-request deadline is required.

## 2. Base Tools

The foreground base allowlist has **25 entries including `tool_search`**.
Only entries actually available in the calling registry are disclosed.

| Purpose | Base Tools |
| --- | --- |
| Discovery and instructions | `tool_search`, `load_skill` |
| User assistance | `request_user_assistance` |
| Memory and Mochi navigation | `recall_memory`, `navigate_mochi_ui` |
| Personal planning | `manage_mochi_calendar`, `manage_mochi_schedule` |
| Local context | `get_current_location`, `get_current_weather` |
| Browser | `browser_read`, `browser_navigate`, `browser_click`, `browser_input`, `browser_scroll` |
| App Control | `app_read`, `app_navigate`, `app_click`, `app_input`, `app_scroll` |
| Media Control | `media_read`, `media_play_from_search`, `media_control`, `media_volume` |
| Computation and delegation | `run_sandboxed_javascript`, `delegate_agent` |

This list does **not** enable disabled groups, grant Android permissions, install
extensions, create logins, or change actor scope. In particular, memory still
requires Daily journal and children never gain delegation, App Control, or
Media Control merely because these names are in the foreground base list.
Scheduled/child runs use the intersection with their existing scoped registry.
`load_skill` exists only when that registry has usable Skills.

Every other authorized Tool is deferred: Amap, document-provider MCP Tools,
AgentLink, optional extensions, custom MCP and custom REST. Their individual
names and supported operations belong only in the executor/provider documents.
New Tools are deferred unless explicitly added to this base policy.

## 3. What the model sees

Initially the model receives base schemas, the existing lightweight Skill
metadata, and instructions to use `tool_search` for additional capabilities.
It does not receive every deferred Tool's name/description or any unselected
parameter schema. The model writes English capability queries containing the
service, operation and object, or an exact Tool name. It translates non-English
user wording itself; users can continue conversing in their preferred language.
This is schema/prompt guidance, not an ASCII or language-rejection rule.
The search entry does not embed the entire catalog.

Searching means **finding a Tool**, not executing it. For example, the query
"Tencent Docs add spreadsheet fields" loads the matching definition; it does
not create fields or contact a document API.

```mermaid
sequenceDiagram
    actor User
    participant Host as Mochi on phone
    participant LLM as Selected model
    participant Index as Local authorized Tool index
    participant Executor as Real Tool executor
    User->>Host: Task
    Host->>LLM: Base tools including tool_search; no deferred schemas
    LLM-->>Host: Call tool_search(query, limit)
    Host->>Index: Match names/descriptions and check live availability
    Index-->>Host: Selected complete definitions within budget
    Host->>Host: Add definitions to this run's visible set
    Host->>LLM: Search result + selected definitions in next request tools
    LLM-->>Host: Call a selected real Tool with arguments
    Host->>Host: Check visibility, live authorization and cancellation
    Host->>Executor: Validate and execute once
    Executor-->>Host: Typed result or explicit error
    Host->>LLM: Matching Tool result and same-run history
    LLM-->>Host: Continue, search again, or give evidence-based final reply
    Host-->>User: Verified result or honest limitation
```

Base Tools can be called immediately, skipping search. The complete Browser,
App and Media groups are therefore ready for common phone tasks without an
extra discovery round. A request to put the resulting shopping comparison into
a document may search for document Tools later in the same run.

## 4. Search and budgets

`tool_search` accepts a nonblank `query` (up to 512 characters), optional
integer `limit` (1-8, default 5), and optional `skill_name`. Unknown fields,
invalid types and empty queries return the normal `INVALID_ARGS` envelope.

### Skill-scoped search

`skill_name` is the exact standard name from this actor's visible Skill
metadata, identical to the name used by `load_skill`: 1-64 lowercase ASCII
letters/digits with single separating hyphens. It is a hard candidate filter,
not a ranking hint, authorization grant or request to load every associated
schema. Omission searches all currently available actor-owned Tools.

The English parameter description tells the model when and how to scope:

> Optional exact Skill name from Available Skills (same as load_skill), to narrow
> capability searches. Omit for exact Tool-name lookups or cross-Skill/unknown
> workflows. No global fallback. Does not require load_skill.

Exact-name lookups already rank first and do not need a Skill lookup; omitting
redundant scope avoids extra arguments and repository reads. If supplied anyway,
the scope still applies strictly.

For example:

```json
{"query":"Add fields to a smart table","skill_name":"tencent-docs-knowledge","limit":5}
```

The shared relation resolver combines original mandatory declarations,
explicit optional Tools and provider-associated names, intersected with the
actor's live available registry. Mandatory/any-provider readiness remains a
separate check; optional Tools never block readiness. Installed Skills without
Tool declarations have an empty scope, not unrestricted search. The host does
not infer associations from translated instructions or maintain a second map.

The model may read a Skill first for workflow guidance, but scoped search does
not force an additional `load_skill` round or translate its body. The last-read
Skill never implicitly changes later searches. The run gates lookup on its
visible Skill metadata and reloads the enabled original Skill before search
and before publication; relation changes or Skill edits/revocation during the
search return `STALE_REF` without loading new definitions.
Named lookup reads current overrides without rebuilding every Skill or reading
unrelated journal permissions. Name/dependency parsing stops at the closing
frontmatter delimiter instead of splitting instruction bodies. Neither
optimization caches live enablement.

| Condition | Result |
| --- | --- |
| Malformed/ambiguous name or invalid argument | `INVALID_ARGS` |
| Unknown, disabled or actor-unlisted Skill | `NOT_FOUND` |
| Skill's mandatory/provider requirements unavailable | `PERMISSION_DENIED` |
| Empty association set or no lexical match in scope | `NOT_FOUND`; explicit suggestion to refine or omit scope |
| Skill or resolved relationship changes while searching | `STALE_REF` |

No error silently falls back to global search. Exact names also obey the
scope; the model must make a separate broader search if appropriate. Scope
does not hide previously loaded Tools or replace execution-time authorization.
Results include `candidate_count` and, when specified, `skill_name`; names of
matches are returned once, without repeating descriptions already supplied in
the next request's full schemas.

### Model-facing language and preparation

Tool descriptions and schema text annotations presented to the Agent are
English. UI/catalog source metadata remains separate and unchanged. Protocol
identifiers, property names, required lists, enum/const/default/example values,
references and validation constraints are preserved, including Chinese literals
required by an upstream API. This policy concerns instructions in schemas,
not user messages, business data or ordinary Tool results. Skill metadata and
the instruction-bearing `load_skill` result follow the
[English Skill contract](TECHNICAL.md#2-runtime).
Recognized annotations are `description`, `title`, `$comment`,
`markdownDescription`, `enumDescriptions`, `x-enumDescriptions` and
`x-enum-descriptions` at function/schema nodes. Arbitrary vendor fields are not
guessed to be prose; integrations must use these annotation fields.

`EnglishToolSchemas` prepares model-facing copies. English/Latin-script text
and punctuation are reused without a model call; this fast path is not a
semantic language classifier. Non-Latin text annotations use the active
connection with a standalone translation prompt, like the journal's isolated
generation path: only annotation IDs/texts are sent, with no persona, history,
memory, unrelated Skills, callable Tools, or complete schema. Skill preparation
reuses this mechanism with its own paragraph-translation prompt and cache-key
namespace. Source text is untrusted
translation data. The typed result must contain exactly the requested IDs and
English text. The host replaces only those annotations in the original
schema; it never accepts a model-generated replacement schema.
If an otherwise correctly identified response retains non-Latin text, one
additional isolated request rewrites only the affected annotations. Both
responses must satisfy the same final constraints; there is no unbounded retry
or language-validation bypass.

MCP connection/discovery/import and REST metadata updates warm the cache after
the configuration transaction, never inside it. Extensions, old configurations
and evicted cache entries are prepared before main/child registry construction.
Content-addressed app-private cache entries include the source schema and
translation-policy version in their key; source changes require preparation
again. Cache size and translation batches are bounded. Cancellation cannot
publish an unfinished result, and active Provider changes cancel translation.
The normal foreground model-request deadline remains unchanged.

Preparation errors surface explicitly and block Agent loading; there is no
fallback to untranslated instructions. Saved connection metadata is retained
if preparation fails and may be retried before use. Invalid derived cache entries
are removed with an explicit error; the next preparation attempt regenerates
them. Cache misses can consume model quota; English schemas and valid cache
hits do not. A final Agent-loop guard
rejects unprepared non-English annotations before any model request.

### Ranking and visibility budgets

Search is deterministic and local:

1. Filter to the current registry, live enabled/connected candidates and the
   optional resolved Skill scope.
2. Prefer exact names; split identifier separators/camel case and normalize
   case/Unicode. Index names and descriptions, not nested parameter prose.
3. Rank lexical matches with BM25 and a name boost. Include Chinese character
   bigrams and a small shared Chinese/English capability vocabulary. It is not
   an embedding model and must not pretend to understand arbitrary synonyms.
4. Select ranked complete definitions subject to count and serialized-byte
   budgets. Ties use stable names. Return loaded names and
   explicit no-match/omitted-budget information.

One immutable index is built per run. Scoped search skips excluded entries
before scoring and Top-K selection, while retaining full-registry document
frequencies and length statistics. It does not rebuild per-Skill indexes or
filter an already truncated global Top-K; eligible Tools keep their original
relative BM25 ordering. There are no embeddings, extra inference requests or
cross-run permission caches.

Budgets count compact UTF-8 JSON, including array separators, not characters
or a fixed number of bytes per token. The next request's visible Tool array
is bounded to 128 KiB; the foreground base set has a 32 KiB regression budget.
These are repository engineering thresholds, not Provider API/context limits
or experimentally established latency optima. The base threshold fails a
regression test rather than truncating definitions at runtime.
Admission reserves two extra bytes per ordinary function for the Responses
adapter's flattening/default `strict:false`, so the cap holds for both APIs.
Search never truncates a schema, deletes required fields, or loads the whole
catalog on a miss. An oversized candidate is explicitly reported; the model
can narrow the query or explain the limitation. Existing definitions are
deduplicated, and search results contain metadata rather than another copy of
the entire schema.

Within a run, loaded definitions remain available without searching again.
Each availability pass reads one catalog snapshot for the requested names,
and at most one live snapshot for each requested extension/AgentLink provider.
Main, scheduled and child registries intersect the answer with their own names;
Skill readiness uses the same batched path. Snapshots are local to a single
pass, never a cross-round permission cache. Search checks again before publishing
its selection, and execution still checks current permission for the individual
Tool before dispatch. Provider executors retain their own revision guards.
New top-level runs start with their own base set. A cancelled search cannot
publish new definitions. Disable/revocation removes visibility and blocks
execution; changing visibility is never an authorization grant. Unsupported
or undisclosed calls return explicit errors rather than guessing or silently
executing a Tool that the model has not loaded.

## 5. API continuation

Both current paths expose the same ordinary **function** named `tool_search`.
Both must return a result for the search call **and** put loaded definitions
in the next request's `tools`.

| Step | Chat Completions | Responses, ordinary-function path |
| --- | --- | --- |
| Search declaration | `type: function`, `function.name: tool_search` | Flattened `type: function`, `name: tool_search` |
| Model requests search | `assistant.tool_calls` | `function_call` |
| Host returns search result | `role: tool`, matching `tool_call_id` | `function_call_output`, matching `call_id` |
| Definitions become callable | Next request's `tools` | Next request's `tools` |
| Real Tool execution | Later `tool_calls` | Later `function_call` |

The search result alone is not a callable definition. Conversely, updating
`tools` without completing the search call breaks the conversation protocol.
Preserve all call/result associations, multiple calls in the same response,
and Responses reasoning/opaque continuation. Final-response repair remains
Tool-disabled. Calls may only use definitions supplied in the request that
produced them; a search cannot authorize a speculative sibling call in the
same model response.

```mermaid
flowchart TD
    A[Next model step] --> B{Needs another Tool?}
    B -->|Yes| C[Call ordinary function tool_search]
    C --> D[Phone searches authorized local registry]
    D --> E[Return search result; add selected schemas to next tools]
    E --> A
    B -->|Already loaded| F[Model calls real Tool]
    F --> G{Live authorization and arguments valid?}
    G -->|No| H[Return explicit Tool error]
    G -->|Yes| I[Execute once and observe result]
    H --> A
    I --> A
    B -->|Task complete| J[Reply with verified result]
    A -. Cancellation or round limit .-> K[Stop; do not replay uncertain writes]
```

Search shares existing Tool-round limits and cancellation. It is not business
evidence for cards. A timeout does not imply a write failed; existing executor
readback/idempotency rules remain authoritative.

## 6. Measurement and acceptance

The base-schema measurement must construct the real Kotlin Tool definitions,
not approximate source-code lengths. Record the ordinary Chat array and the
actual Responses-adapter array separately, including `tool_search`. Prompts,
Skill bodies, conversation history, results and HTTP headers are additional.
Do not convert byte counts into claimed model token usage without tokenization.

`BaseToolSchemasTest` constructs all real foreground base definitions with
fail-fast dependencies, asserts exact membership of the base policy, and
serializes both protocol arrays without executing any Tool or calling a model.
Current measurement (all 25 available; compact UTF-8 bytes):

| Set | Chat Completions | Responses |
| --- | ---: | ---: |
| Complete 25-entry base set | **20,902 bytes (20.41 KiB)** | **20,952 bytes (20.46 KiB)** |
| `tool_search` definition | 1,037 bytes | 1,039 bytes |

The largest individual Chat definitions are `manage_mochi_schedule`
(1,877 bytes), `navigate_mochi_ui` (1,824), and `recall_memory` (1,704).
Actual actor runs may send less when a base capability is unavailable.

The base array occupies about 64% of the 32 KiB regression budget and avoids a
discovery round for common Browser, App and Media tasks. Large or uncommon
provider schemas stay deferred.
This is a payload-size decision, not a measured token, accuracy or end-to-end
latency guarantee. The regression test fails if either protocol's actual base
array crosses 32 KiB; rerun it when a base schema changes.

Acceptance covers:

- All available base Tools work without search, including App and Media.
- Unrelated document schemas are absent initially, but discoverable by
  English capability queries and exact names. Non-English queries are not
  rejected by a character-set validator.
- Search returns its own result, the next request gains complete schemas,
  and real calls then execute on both supported APIs.
- Invalid/no-match/budget-limited searches, undisclosed calls, same-response
  speculative calls, cancellation, revocation and separate runs fail safely.
- Skill readiness remains based on full scoped availability.
- Optional Skill scope uses the shared relationship resolver, filters before
  Top-K, never widens silently, and rechecks edits/revocation before publication.
- Search results omit descriptions duplicated in next-request schemas.
- Payload-size regression checks protect the base budget; end-to-end
  task latency/selection quality still need real deployment/device evaluation.

Small schemas reduce transfer overhead; they do not fix TCP/TLS delays or
guarantee every model request finishes before the unchanged foreground limit.

### Device measurements

Measured on 2026-11-21 with a Xiaomi M2012K11AC (alioth, ARM64), debug app
version 1.0.20, unchanged connections/permissions and warm English caches.
There were 74 catalog-ready Tools and 77 observed registry schemas.
Fixtures and opt-in reproduction instructions are in
[Development](../DEVELOPMENT.md). No private history/persona/memory is used.

**Paired retrieval:** 44 fixed English queries plus 53 generated exact names;
89 positive queries run both globally and with Skill scope, alternating pair
order. Eight no-dedicated-integration negatives run globally, for 186 searches.
Every observed ranking agrees with the in-memory index; global ranking is
unchanged. Top-1/Top-5 mean one designated target ranks first/within five,
not exhaustive relevance precision or business-task success.

| Query group | Cases | Global Top-1 / Top-5 | Scoped Top-1 / Top-5 |
| --- | ---: | ---: | ---: |
| Explicit service + operation + object | 24 | 23/24 / 24/24 | 23/24 / 24/24 |
| Manually authored natural paraphrases | 12 | 1/12 / 7/12 | 2/12 / 8/12 |
| Exact deferred names | 53 | 53/53 / 53/53 | 53/53 / 53/53 |

Across the 36 descriptive queries, Top-5 target recall is **86.1% globally
and 88.9% scoped**. Scope removes cross-provider interference, not within-provider
lexical mismatches such as "columns"/"fields" and "rows"/"records".
The manually authored paraphrases are not queries generated by the main model.
Six of eight negative queries return generic/unrelated candidates; a nonempty
result is not proof of dedicated service support. Browser/App workarounds may
exist. This curated set is not a production accuracy estimate.

For the same 36 descriptive-query pairs:

| Metric | Global median / P95 | Scoped median / P95 |
| --- | ---: | ---: |
| Eligible candidates | 77 / 77 | 23.5 / 35 |
| Warm BM25 scoring | 0.131 / 0.170 ms | 0.102 / 0.137 ms |
| Search through next request's schema readiness | 41 / 51.25 ms | 57 / 67 ms |
| Search result envelope | 359 / 396 bytes | 375 / 434 bytes |
| Next-request Tool array | 27,404 / 71,743 bytes | 27,393.5 / 71,743 bytes |

BM25 measurements use five warmup passes and 720 timed samples per variant.
Host timings include current permission/schema checks and, for scope, fresh
Skill resolution and revalidation; they exclude factory work, model inference
and business execution. Scoping saves scoring work but adds about 16 ms to
the measured median host path. Exact-name queries should omit redundant scope.
Phone scheduling, GC and thermal state are not controlled; these are observations,
not latency guarantees.

**Payload:** for the same 36 descriptive queries, the current result envelopes
total 12,316 bytes globally and 13,385 bytes scoped. Adding duplicate descriptions
from the returned schemas to those same results would produce 35,018 and
35,987 bytes respectively: omitting duplication saves **64.8% / 62.8%**.
The next-request Tool arrays total 1,186,072 / 1,188,623 bytes; this set does not
establish schema-payload savings from scope alone. These are compact UTF-8 bytes,
not tokenizer counts or billed tokens. Scope narrows eligible Tools but does
not guarantee fewer matches, smaller definitions or a faster overall turn.

**Chinese request to main-model query:** 44 synthetic tasks on the active
`gpt-6.1-terra` connection, Responses, with the normal 20-second request
deadline. The diagnostic permits only `load_skill`/`tool_search` and stops at
the next request boundary after the first search batch, before business execution.

| Observed route | Positive tasks (36) | No-dedicated-integration tasks (8) |
| --- | ---: | ---: |
| Reached search | 17 | 8 |
| Assistance/location call intercepted before dispatch | 15 | 0 |
| Replied without search | 4 | 0 |
| Provider error or timeout | 0 | 0 |

All **25 search queries were English**; six of the 17 positive queries used a
valid related Skill scope, with no scope errors or out-of-scope results.
All seven exact-name positive queries ranked their named Tool first.
The original task's final target appeared in the first batch for **12/17**
searched positive tasks (11 at rank one). Four other tasks first searched a
file-location prerequisite; one compound table-search/update query missed its
final update Tool. These are distinct from scope-selection errors. Missing
document identifiers can legitimately require assistance or prerequisite
lookup; conditional retrieval hits do not establish success across all 36 tasks.
Six of eight negative queries returned generic/unrelated candidates.

| Live phase | Samples | Median / P95 |
| --- | ---: | ---: |
| Individual model request | 69 | 2,638 / 3,908.6 ms |
| Search response through next request readiness | 25 | 146 / 261.8 ms |
| Agent start through search results, excluding factory | 25 | 3,576 / 5,895.4 ms |
| Factory preparation for searched cases | 25 | 472 / 561.8 ms |

Live host timings can include same-response Skill loads and multiple searches;
model/network conditions and phone scheduling vary. Stage medians are not
additive, and the zero observed timeouts do not guarantee future requests.

Phone verification also covers all 13 enabled Skills' English model inputs and
a guarded live chain through search, next-request schema disclosure, one public
Shanghai Amap weather call and a successful final reply. This does not certify
arbitrary provider writes or full business-task accuracy.
