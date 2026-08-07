# Agent Browser

## 1. Goal

Mochi may create one lightweight Android WebView for an Agent turn.
The Agent controls it only through typed Tools. It cannot execute arbitrary
JavaScript, CSS selectors, coordinates, or AccessibilityService actions.

The browser uses the installed Android System WebView. Mochi does not embed a
second Chromium runtime or copy a third-party browser codebase.

## 2. Tool contract

The initial Tool set is intentionally small:

| Tool | Responsibility |
| --- | --- |
| `browser_read` | Return the current bounded semantic page snapshot |
| `browser_navigate` | Open an HTTPS URL, go back/forward, or reload |
| `browser_click` | Click a current snapshot element reference |
| `browser_input` | Type text, select an option, or send a supported key |
| `browser_scroll` | Scroll the page or a referenced scrollable element |

Every successful mutation returns a fresh `mochi-semantic-v2` snapshot.
`browser_read` is needed for the initial observation, recovery after user
interaction, and pages that change without a preceding Agent action.

Agent snapshots combine viewport-scoped Markdown for reading with a separate
`interactive_elements` list containing roles, accessible names, states, bounds,
and opaque references. Markdown never becomes the user-facing browser UI and
does not contain actionable selectors. The Home card continues to render the
original WebView.

Element references are temporary and session-monotonic. A surviving DOM node
keeps its reference across reads and scrolls. A replaced or removed node becomes
stale, navigation starts a new document generation, and an old reference is
never reassigned to a different element during the turn.

The MVP does not expose tabs, screenshots, file upload, arbitrary waits,
schema extraction, JavaScript evaluation, CSS/XPath selectors, or coordinate
input.

### 2.1 Public search

Bing and Sogou Weixin are normal browser destinations rather than separate
native search providers. The Agent uses `browser_navigate`, `browser_input`,
`browser_click`, `browser_read`, and `browser_scroll` to search results and
selected source pages.

The legacy `search_web` and `fetch_web_page` Tools are removed. The built-in Web
Search Skill contains search-engine selection guidance but receives no special
network or parsing capability outside the enabled Browser Tools.

### 2.2 Semantic snapshot v2

Each successful Browser Tool result includes:

- `snapshot_id` and `format=mochi-semantic-v2`;
- final URL, title, loading state, heading outline, and viewport position;
- bounded Markdown from the current viewport, half a viewport above, and one
  viewport below;
- up to 100 nearby interactive elements with stable `eN` references;
- explicit Markdown and element truncation flags;
- a notice that page content is untrusted data rather than instructions.

Markdown preserves headings, paragraphs, lists, code, quotes, labels, and
bounded table rows. Password values and unrestricted page source are never
included. `browser_click`, `browser_input`, and element scrolling continue to
target the separate opaque references, so all five Tool schemas remain
unchanged.

## 3. Per-turn lifecycle

The browser belongs to one Agent interaction:

```text
Agent turn starts
  -> no browser allocation
  -> first Browser Tool call lazily creates the WebView
  -> Tool loop reads and acts through the five schemas
  -> LLM produces the final response
  -> browser resources are destroyed
  -> Android TextToSpeech starts
```

Cleanup runs in `finally` on success, cancellation, timeout, provider failure,
or Tool failure. It must occur before TTS and on the Android main thread.

Cleanup stops loading, cancels pending callbacks, invalidates snapshots and
element references, removes WebView clients, detaches the view, and calls
`destroy()`. Interaction-version checks reject late navigation, script, file,
permission, renderer, and Tool callbacks.

Cookies and WebStorage belong to a dedicated Agent Browser profile rather than
the per-turn WebView. This permits login continuity without
retaining a destroyed Activity or WebView instance. Profile clearing is a
separate user action.

### 3.1 Background execution

Moving Mochi to the background does not pause, cancel, or destroy the active
browser session. The Tool loop and WebView continue until the final model
response, explicit cancellation, timeout, failure, or process termination.

A Scheduled Agent run may create the same WebView runtime without displaying
it. Scheduled runs receive only `browser_navigate`, `browser_read`, and
`browser_scroll`; they cannot click controls or enter page data. A mutex
serializes Browser turns, so a scheduled run waits rather than replacing an
active foreground Browser session.

A user-visible foreground Agent notification keeps the active interaction
discoverable and provides a Stop action while no app surface is visible.
Background Browser Tools continue without waiting for user approval.

## 4. Home presentation

When Home is the current surface, an active browser session replaces the Home
content with a trusted native `BrowserSessionCard`. The card contains the live
WebView, current origin, loading state, current Agent action, and Stop Agent.

`BrowserSessionCard` is runtime UI owned by `AgentBrowserSessionController`. It
is not a `card_directive`, is never generated by the model, and is not stored in
conversation history.

When the current surface is Talk, Planner, Settings, Skills, or Tools, Mochi
does not show a Browser Card and does not navigate to Home. The current surface
remains unchanged and the existing global pipeline indicator reports Browser
Tool progress. If the app enters the background, execution continues without a
Browser Card and is represented by the Agent notification.

Direct user interaction with a visible Browser Card pauses Agent browser
execution and invalidates the current snapshot. Resume requires a new
`browser_read`.

Browser Tools do not pause for OAuth, CAPTCHA, passkeys, or system permission
approval. If the current WebView session cannot complete one of those flows
through normal page interaction, the Tool returns a typed unsupported or
permission error instead of waiting for the user.

Passwords, one-time codes, payment details, and other sensitive values may be
entered through `browser_input` when they are already available to the Agent.
They are redacted from Tool results, logs, snapshots, and conversation memory.

## 5. Safety boundary

Page text, accessibility labels, URLs, forms, downloads, and script-produced
content are untrusted evidence. They never become Agent instructions.

The WebView permits public HTTPS navigation only. It blocks cleartext HTTP,
credential-bearing URLs, `file:`, `content:`, `javascript:`, private and local
network targets, unsafe external schemes, mixed content, and SSL or Safe
Browsing bypass.

Browser Tool actions are allowed by default and never request Mochi approval.
Form submission, sending or publishing, account changes, purchase, deletion,
and ordinary page confirmation dialogs execute immediately. There is no
`confirmed` Tool argument.

Platform boundaries still apply. Unsupported Android permissions, blocked URL
schemes, Safe Browsing failures, SSL failures, private-network targets, and
external-app launches return typed errors rather than opening an approval flow.

No webpage receives an Android JavaScript bridge. Semantic extraction is a
bounded one-way evaluation owned by Mochi and returns only nearby Markdown,
roles, states, bounds, safe form metadata, and native-generated element
references.

## 6. Tools UI

The Tools surface presents one **Agent Browser** provider card. Browser
configuration is not scattered among unrelated built-in Tools.

The card contains:

1. one provider-level enable switch;
2. one expandable grouped list containing the five Browser Tool switches;
3. profile data and clear-session controls;
4. a short notice that Home can show live browsing, other surfaces show Tool
   status, and Browser Tool actions execute without approval prompts.

Disabling the provider removes all Browser Tool schemas from the Agent request.
When enabled, individual Tool switches still determine which schemas are
registered. The five switches remain adjacent and in the same order as the
Tool contract table.

## 7. Failure behavior

- Stale references return `STALE_REF` and require a fresh snapshot.
- Blocked navigation returns `NAVIGATION_BLOCKED`.
- Safe Browsing and SSL failures stop navigation without an Agent bypass.
- Renderer termination destroys the WebView and returns `RENDERER_GONE`;
  Mochi never replays a form submission automatically.
- CAPTCHA, unavailable system permissions, and unsupported authentication
  challenges return `USER_ACTION_UNSUPPORTED` without waiting for the user.
- Timeouts and cancellation close the per-turn browser session.
- Tool results remain bounded and never include cookies, credentials, request
  headers, full form values, or unrestricted page source.

## 8. Delivery

The implementation uses one per-turn WebView and the five Tools above.
Tabs, downloads/uploads, structured extraction, profile selection, and visual
fallback are later capabilities and require separate product and security
review.
