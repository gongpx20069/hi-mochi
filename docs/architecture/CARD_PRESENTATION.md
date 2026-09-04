# Card Presentation Architecture

## 1. Purpose

Mochi cards are trusted native visualizations of an Agent reply. For each
individual assistant message, a valid card replaces only that message's
duplicate text bubble. Messages without a card use the normal text bubble. The
accepted reply text remains available for TextToSpeech, conversation history,
and fallback.

The implementation is intentionally smaller than a general server-driven UI
engine. The model cannot generate Kotlin, Compose, HTML, JavaScript, colors,
dimensions, or arbitrary actions. Android owns the component catalog, styling,
responsive layout, Focus behavior, and navigation policy.

## 2. Separation of responsibilities

| Layer | Responsibility |
| --- | --- |
| Tool | Return factual structured data |
| LLM | Select one card template, placement, title, evidence sources, and optional actions |
| Orchestrator | Retain successful Tool evidence and bind it to the request |
| Presentation policy | Resolve Home, inline, or deferred placement |
| Android renderer | Render only the trusted Compose catalog |

`card_directive` is part of the final Agent response. It is not a Tool because
presentation has no external side effect and should not add another Tool round.

`BrowserSessionCard` is a separate runtime-only Home presentation. It is owned
by the native Agent Browser controller, contains a live WebView, cannot be
requested through `card_directive`, and is never stored as an assistant-message
card. Talk, Planner, Settings, Skills, and Tools do not render it; those
surfaces retain their content and show Browser Tool work only through the
global pipeline indicator.

## 3. Agent response

```json
{
  "reply": "You have two meetings and three active tasks today.",
  "emotion": "neutral",
  "ui_directive": null,
  "card_directive": {
    "type": "daily_briefing",
    "placement": "home",
    "title": "Today at a glance",
    "sources": ["weather", "calendar", "todos"]
  }
}
```

Supported card types:

- `daily_briefing`
- `agenda_timeline`
- `todo_focus`
- `content`
- `research_summary`
- `comparison`
- `insight`
- `progress`
- `camera_snapshot` (deterministic extension attachment only)

Supported requested placements:

- `auto`
- `home`
- `inline`

Supported evidence sources:

- `weather` -> `get_current_weather`
- `calendar` -> `manage_mochi_calendar`
- `todos` -> `manage_mochi_todo`
- `browser` -> successful same-run Agent Browser evidence

`content` is the general external-content card for web, Notion, Tencent Docs,
and other MCP or Tool results. The model may omit `type` because `content` is
the default, provide bounded `body` and `items`, and optionally select exact
successful Tool names through `evidence_tools`. Returning no `card_directive`
keeps the normal text bubble.

Unknown types, placements, sources, malformed fields, invalid actions, or cards
without required Tool evidence are ignored. The valid text reply still succeeds
and is rendered as the normal assistant bubble.

`camera_snapshot` is not model-authored general content. A successful
foreground `mijia_get_latest_camera_event_image` result creates it
deterministically after the host validates same-run evidence and an attachment
from the official extension. The renderer prioritizes the image inside the
available portrait or landscape viewport. Event provenance, metadata,
model-input state, and Dismiss are overlaid on the image so the photo remains
the primary card content. It shows only:

- the locally decoded image;
- camera name;
- home and room;
- event type when available;
- event capture time;
- a clear **latest event / not live** label.

The card permits Dismiss and the existing placement/expand behavior, but no
Save, Share, or Open source action. It states whether the normalized image is
available only inside the current foreground run, including at most one
explicit Subagent handoff under the provider's permission, or stayed
device-only. The card and image are never persisted.
Expired, cancelled, stale, oversized, malformed, or undecodable attachments
discard the card while preserving the text reply.

## 4. Evidence binding

The Orchestrator retains successful JSON Tool results for the current run only.
Typed local cards reference logical source names, and Android binds temperature,
humidity, event titles, and todo content directly from retained Tool results.
General content cards allow the model to summarize retained external evidence
into bounded text and item fields. Android still validates the schema and binds
source links only from successful Tool results.

This prevents a card from displaying facts that differ from the Tool output.
Evidence is bounded before rendering:

- up to four metrics;
- up to eight list items;
- up to six public sources;
- bounded title, subtitle, and body text.

Card evidence is not added to conversation history. Later turns receive the
normal assistant text history and must call Tools again when current facts are
required.

## 5. Presentation policy

`ui_directive` controls page navigation. `card_directive` controls visual
content. They are resolved together with these priorities:

1. Explicit validated `ui_directive`.
2. Protected-surface rules.
3. Current Home or Conversation surface.
4. Requested card placement.
5. Safe default placement.

Rules:

| Situation | Result |
| --- | --- |
| Explicit UI navigation plus a card | UI navigation wins; card is deferred |
| Explicit Conversation navigation plus non-Home card | Inline card |
| Any valid card while a Home presentation is active | Home card |
| `home` while not in Settings or Skills | Home card |
| `inline` while in Conversation | Inline card |
| `inline` on another non-Home surface | Deferred card |
| `auto` in Conversation | Inline card |
| `auto` in Settings or Skills | Deferred card |
| Other `auto` requests | Home card |

Deferred cards remain attached to the assistant conversation message. They do
not interrupt Settings or Skills and become visible when the user opens Talk.

Current date/time and current weather remain deterministic exceptions:

- date/time uses the existing live DateTime Home presentation without a Tool;
- weather uses `get_current_weather` and the existing Weather presentation;
- the final response must include the matching validated `ui_directive`;
- the model must not request a generic card for either case.

## 6. Rendering

Home and inline are placements of the same `CardPresentation`, not different
card kinds. Both use the same trusted content and Action renderer. Home cards use
the existing Home transition, bottom-navigation selection, Focus mode, rotation
persistence, and responsive full-area layout. Restoring Mochi returns to Face.

Inline cards replace the duplicate assistant bubble in Talk. The accepted reply
text is still spoken and retained as fallback. Expanding an inline card reuses
the same card ID and content on Home:

- title and subtitle;
- hero value;
- metrics;
- bounded list items;
- source labels;
- explanatory body text.

Supported actions are Android-owned enum values:

- `open_today`;
- `open_calendar` with a validated ISO date;
- `open_talk`;
- `complete_todo` with an ID from same-run todo evidence;
- `open_source` with a zero-based index into retained source evidence;
- `expand`;
- `dismiss`.

The model may request at most three actions. It cannot provide executable code
or a raw action URL. Android adds contextual Expand and Dismiss controls when
needed, opens only retained HTTPS sources, and updates all placements sharing
the card ID after a state-changing action.

## 7. Chat pipeline

```text
Listening
  -> Skilling / Planning
  -> Tool execution
  -> successful Tool evidence collection
  -> final reply + optional directives
  -> directive validation
  -> evidence binding
  -> placement resolution
  -> atomic navigation/state update
  -> Compose presentation
  -> TextToSpeech
  -> follow-up Listening or wake capture
```

The spoken reply is always the accepted assistant text, except for the existing
deterministic date/time and weather normalization that guarantees displayed
facts are also spoken.

## 8. Future extension

If the Agent later moves to a server, AG-UI can transport run, Tool, state, and
card events without replacing this Card Catalog. The trusted Android
presentation model remains the rendering and safety boundary.
