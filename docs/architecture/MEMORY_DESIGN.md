# Memory design

This document owns the current conversation-memory, Daily journal and explicit
recall architecture. It describes implemented behavior, not a changelog or a
future memory system. Product, interaction and Tool permissions follow the
[documentation authority rules](../README.md#authority).

## 1. Current design

Three paths share saved evidence without becoming one execution pipeline:

| Path | Purpose | Activation |
| --- | --- | --- |
| Automatic conversation context | Recent messages plus relevant older messages in the normal Agent request | Existing conversation flow; independent of Daily journal |
| Daily journal | A concise derived account of a closed day, using conversations, user schedules and Mochi runs | Default-off Daily journal setting; independent Worker |
| `recall_memory` + `memory-recall` Skill | Explicit search and bounded reading of journals and original conversation turns | Foreground Main Agent only; governed by the same Daily journal setting |

There is one chronological conversation store, not separate short-term and
long-term databases. Journals have their own date-keyed table and FTS index.
There are no embeddings, event/claim knowledge tables, query-planning model,
or automatic copying of journal paragraphs into conversation history.

### 1.1 Ownership and persistence

| Component | Owns |
| --- | --- |
| `AgentMemoryRepository` | Successful-turn persistence, `loadContext`, automatic recall formatting |
| `MemoryLexicalSearch` | Shared ICU tokenization, portable FTS4 query construction, automatic-recall lexical ranking |
| `JournalRepository` / `JournalDao` | Journal evidence, settings, lifecycle, claim-fenced publication and synchronized journal index |
| `RoomMemoryRecallStore` / `MemoryRecallDao` | Explicit time-filtered search, versioned journal reads, complete conversation turns and bounded text pages |
| `RecallMemoryTool` | Typed argument validation, live access/revocation checks and result envelopes |
| `SkillRepository` / `ToolCatalogRepository` | Visible managed entries, readiness and actual setting-derived enablement |
| `MochiAgentFactory` | Foreground-only registration before Skill readiness/discovery |
| `JournalViewModel` / journal UI | Shared settings and disclosure; Planner and Settings controls |

Room schema 9 stores `agent_memories`, `agent_memory_fts`, `daily_journals`,
`journal_search`, and `journal_settings` alongside other application data.
Upgrade migrations preserve saved records, rebuild the journal index from
retained content, and do not enable Daily journal.

Messages retain ID, `turn_id`, role/type, content, tokenized `search_text`,
original timestamp and optional `source_run_id`. `saveTurn` trims both strings,
skips an empty pair, and writes a shared turn ID plus both message/FTS rows in
one transaction. Each saved message has a 20,000-character storage cap.
Text beyond that cap cannot be recovered.

Foreground persistence saves successful user/final-assistant pairs; failed
foreground completion does not durably capture its input. Intermediate Tool
traces remain run-local. Scheduled callers also write to the store, including
failure descriptions; tagged copies are not user statements.

### 1.2 Automatic context flow

1. Read the latest `2 * N` eligible messages and reverse into chronological
   order. DataStore owns `N`: default 20 turns, allowed 1-50.
2. Tokenize the current input. Search older messages, excluding recent IDs in
   SQL **before** ordering and limiting. Joint primary terms are preferred;
   OR matches fill remaining slots, excluding already selected IDs.
3. Rank at most 150 distinct candidates and select at most five hits.
4. Read one earlier and one later **message** per hit in a batched query.
   This is not complete-turn expansion.
5. Deduplicate by ID, exclude recent IDs again, and sort by original time.

Recent history retains `user`/`assistant` message roles. Older evidence enters
`Relevant memories` with original time, UTC offset, speaker and stored text;
the device timezone is declared once. The current request is appended once.
No match adds no unrelated recency filler. History display uses `loadContext`
with an empty query.

The automatic path needs at most four DAO reads: recent history, preferred
matches, optional OR matches and optional neighbors. Row limits do not bound
all SQLite scan/sort work or end-to-end latency.

### 1.3 Shared lexical search

Tokenization splits mixed Latin/Han runs, normalizes case, uses Android ICU
word boundaries, retains numeric runs, and adds Han bigrams/unigrams as
fallbacks. Index text contains at most 256 terms; lookup uses at most 16.
Terms of at least two code points are preferred; single-code-point terms are
used only when no specific terms exist.

Primary words form the conjunction; generated Han fragments do not add
conjunction requirements. Quoted terms separated by spaces implement portable
FTS4 conjunction, rather than relying on enhanced explicit `AND` syntax.
Terms are escaped and bound, never treated as model-supplied SQL/FTS syntax.
Lexical retrieval can miss paraphrases or words outside the persisted index
term limit. A search miss is not proof that the original event never happened.

### 1.4 Automatic relevance ranking

Automatic recall scores candidate messages deterministically:

```text
sum(matched term weight * inverse candidate frequency)
+ 2.0 * weighted query coverage
+ 1.5 when the normalized full query phrase occurs
+ recency bonus in [0, 0.1]

inverse candidate frequency(t) =
ln((candidate count + 1) / (candidates containing t + 1)) + 1
```

A Han unigram weighs 0.25, another single-code-point term 0.6, and a longer
term `1 + 0.1 * min(length - 2, 4)`. Phrase comparison normalizes case and
removes non-letter/digit characters. Frequency is measured within candidates.
Ranking reuses persisted terms instead of segmenting every candidate again.
This is neither embedding similarity nor an event-membership probability.

### 1.5 Explicit journal-guided recall

The built-in **Memory Recall / 回忆检索** Skill is first among Skills in Capabilities, with stable
ID `builtin:memory-recall` and discovery name `memory-recall`. It requires
`recall_memory`; metadata enters `available_skills` only when enabled and ready.
`load_skill` supplies concise guidance on demand, not a large permanent prompt.

Its default workflow is journal keyword search, candidate dates, conversation
search in the returned natural-day windows, then reading relevant turns.
Exact dates/quotes and today's records can go directly to conversations.
Existing context can already suffice. A journal miss falls back to original
conversations, and an event's scheduled time must not be mistaken for chat time.
Broader time windows may be needed. Journals may describe schedules/runs that
have no matching conversation.

`recall_memory` has two read-only operations:

- `search`: `source` is `journals` (default) or `conversations`; optional
  keywords and half-open `start`/`end` bounds. An empty query requires both
  bounds. Journal bounds are date labels; conversation bounds accept dates
  in device timezone or explicit offset datetimes. Journal results include
  their frozen timezone and `windowStart`/`windowEnd` for precise follow-up.
- `read`: an exact returned `ref`, optional `offset` and `neighbors` (0 or 1).
  Reads a journal or completes the selected conversation turn, optionally with
  adjacent turn context. Context can cross the original search's time bounds;
  original timestamps remain visible.

Unlike `loadContext`, explicit search includes recent saved messages and does
not depend on the recent-turn setting. Time filters apply in SQL before limits.
Multiword search uses the primary-term conjunction when it has matches,
otherwise OR; results report the matching mode and are newest-first with
deterministic ties. It is not the automatic path's 150-candidate ranking.
Use fewer/different keywords or a broader window when necessary.

Search returns five results by default, at most ten, each with a
400-character excerpt and explicit text offset/truncation. `nextOffset`
continues result pages with otherwise identical arguments.
Search offsets are capped at 10,000; `refineSearch` explicitly requests narrower
keywords/time bounds if more results remain beyond that paging limit.
Read pages contain
at most 6,000 text characters; their `nextOffset` continues text, without
silently dropping the remainder or splitting surrogate pairs.
Full-turn reads reject unexpectedly oversized stored turns rather than
misrepresenting partial turns as complete.

Results distinguish `derived_journal`, `saved_conversation` and
`scheduled_task_copy`, retaining dates/timestamps, roles, run IDs and journal
generation/status metadata. Journal references are versioned; deletion or
replacement makes stale references fail rather than return unrelated text.
Failed regeneration keeps the previous text and its index. Successful
publication replaces the index transactionally; deletion removes it.
Deleted, contentless and nonpast journal dates are not search results.

### 1.6 Permissions and boundaries

Daily journal is the sole enablement owner. Skill overrides and Tool toggles
cannot independently enable or disable recall; native setters reject them.
Both catalog entries remain visible with a managed status; their details show
a grey read-only switch reflecting current state. The clickable control explains the relationship and opens Settings
at the Daily journal card.

The Tool exists only in foreground Main Agent registries, not scheduled Agents,
children, or the journal generator. Each call checks enabled state before
reading and the persistent settings revision before returning. Disable/re-enable
cycles revoke already-running reads. A stale registry cannot bypass this check.

Consent explains that relevant excerpts, including earlier saved history, may
be sent to the selected AI while answering. The setting's `since` date limits
automatic journal generation, not authorized explicit history retrieval.
Disabling retains records and does not change successful-turn persistence,
recent context, or existing automatic recall. Already supplied model context
cannot be retroactively retracted.

Retrieved text is evidence, never instructions. Assistant replies are not
automatically user decisions, old plans are not completed/current facts, and
summaries are not verbatim sources. Automatic recent/older context still has
no unified byte/token cap; explicit recall's page budgets do not change that.

### 1.7 Daily journal

The independent `core/journal` summarizer uses WorkManager adapters in
`platform/journal` and UI in `feature/journal`. It does not run Main Agent,
load persona/Skills, call Tools, or automatically recall older history. It
shares the selected Provider transport and cancellation coordinator; changing
that connection interrupts generation. Journals enter only their own store
and index, not chat or automatic context.

After midnight, native code chooses a closed date, freezes its timezone, and
reads a transactionally consistent saved-source snapshot:

- `conversation_history`: saved user/assistant messages in that natural-day
  window, independently paged. Tagged scheduled copies are excluded because
  run records provide that evidence; legacy untagged copies may overlap.
- `user_schedules`: expanded calendar occurrences and dated todos, with
  recurrence and completion interpreted at day end.
- `mochi_tasks`: persisted run names/prompts/results and known due plans.
  Results after cutoff are withheld. Queued/running/planned do not mean success.
  Current-rule projections are explicitly unverified historical projections.

Every schedule/task fragment has `recurring: true`, `false`, or `null` for
unknown. A run uses the current plan's recurrence only when revisions match;
edited/deleted plans cannot prove historical rules. Repetition alone does not
make an important development irrelevant.

Each model request has exactly two messages: fixed English system instructions
with native date/timezone/window/output language, and JSON evidence as the
user message. All evidence is untrusted. Selection retains distinct supported
important facts, merges duplicates, separates plans/outcomes, and omits routine
repetition, withdrawn unacted requests, and technical failures without meaningful
impact on an important user matter. Titles describe retained events, not an
assistant operations/status log.

Final titles have a hard 50-character limit. Paragraphs aim for 300 characters,
usually one or two sentences, but may exceed the target for important facts.
No minimum, filler, truncation-to-target or unsupported inference is allowed.
Intermediate fact notes retain coverage without the final writing targets.
The only response shapes are:

```json
{"status":"written","title":"A short event title","text":"A concise paragraph."}
{"status":"empty","title":"","text":""}
```

Native validation rejects malformed JSON, Tool calls, inconsistent content,
titles over the stage limit (80 for intermediate labels), and text over the
1,600-character response safety bound. Safety-bound failures are explicit,
not shortened success. Empty input publishes `NO_DATA` without a model call;
a model finding nothing important publishes `EMPTY`.

Large days use bounded batches and recursively consolidated `chunk_summaries`.
Generation permits at most 512,000 serialized source characters, 64 calls and
eight minutes. Exceeding those budgets fails explicitly. Intermediate summaries
remain lossy, not a replacement for original records.

Lifecycle is `WAITING -> GENERATING -> READY / EMPTY / NO_DATA / FAILED`.
Enabling starts at the current date. Recovery covers at most seven enabled
past days; older existing entries need manual retry. Unique per-date work and
claim tokens fence publication. Generation time and source counts are published
with text; unknown metadata remains unknown. Retries preserve old content until
successful replacement. Disabling invalidates pending claims before cancellation.
Deletion clears content/index but retains a `DELETED` tombstone to prevent
automatic recreation.

Background/network restrictions can delay midnight work. Boot, clock/timezone
changes and app resume reconcile quietly, without exact alarms, notifications
or speech. Failed unsaved turns, truncated original messages, deleted schedules
and prior versions of edited plans cannot be reconstructed.

## 2. Diagnostics and verification

Deterministic regressions live in `AgentMemoryRepositoryTest`,
`MemoryRecallTest`, `MochiDatabaseMigrationTest`, `ToolCatalogTest`, and journal/
settings UI tests. Build and device-safety requirements are in
[Development](../DEVELOPMENT.md#build-and-verify).

`AgentMemoryLatencyTest` is an opt-in physical-device diagnostic for the
automatic `loadContext` path, not explicit recall, journal quality, model
latency or end-to-end voice performance. It uses isolated synthetic databases,
not real history, and emits `MEMORY_LATENCY` JSON.

After installing same-signer matching-version debug/test APKs in place:

```powershell
adb -s <serial> shell am instrument -w `
  -e class com.example.mochi_pet.core.memory.AgentMemoryLatencyTest `
  -e mochiMemoryLatency true `
  com.example.mochi_pet.test/androidx.test.runner.AndroidJUnitRunner
```

Require `OK (1 test)`, inspect interactive flags, and record power/thermal
conditions. Do not equate sleeping-device measurements with foreground latency,
or synthetic lookup results with real recall quality. Never uninstall, clear
user data, change permissions, or invoke real model processing for a diagnostic
without the corresponding authorization.
