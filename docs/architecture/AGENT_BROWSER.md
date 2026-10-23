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
| `browser_navigate` | Open an HTTPS URL, go back/forward, or reload; select `mode=auto/mobile/desktop` |
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

`auto` uses the exact host's remembered mode, otherwise desktop, for both Agent
and manual browsing. Explicit mobile/desktop choices still win; legacy catalog
entries with an omitted `lastMode` keep their former mobile meaning. Mochi chooses
the presentation without asking users to configure it; legacy manual mode
preferences are ignored and dropped on the next catalog write. Desktop
mode derives its UA from the installed Chromium version, requests a wide
viewport (980 CSS pixels), and sets desktop client hints when supported.
It remains Android WebView, not desktop Chrome or an anti-detection bypass.
Mode changes invalidate references, are limited to four per turn, and cannot
follow page interaction; reload/history are also blocked after interaction to
avoid replaying submissions. History navigation accepts only `auto`.

Foreground Main also receives `request_user_assistance(kind, message, options)`.
This native Tool is available independently of Browser enablement for questions;
browser handoff requires an already open page. Children and schedules do not
receive it. The Agent generates the explanation and optional choices in the
user's language. The call suspends on an ID-correlated deferred answer, without
model polling, for at most ten minutes. Results are `completed`, `cancelled`, or
`timed_out`; completion is not proof of successful login. Re-read to verify.
Cancellation abandons the pending request and rejects late/duplicate answers.

### 2.1 Public search

Bing and Sogou Weixin are normal browser destinations rather than separate
native search providers. The Agent uses `browser_navigate`, `browser_input`,
`browser_click`, `browser_read`, and `browser_scroll` to search results and
selected source pages.

The legacy `search_web` and `fetch_web_page` Tools are removed. The built-in
Browser Skill covers direct website access, reading, comparison, session reuse
and search-engine selection, without special network or parsing capabilities.
It replaces Web Search while preserving its saved enabled/disabled preference.
Navigate/read/scroll are its prerequisites; click/input/assistance are optional,
registry-scoped capabilities. Its instructions distinguish automatic WebView
cache/session reuse from native-only saved credentials. It cannot read, select
or fill vault accounts, nor infer current login or freshness from stored data.
Product, ratings, stock and travel Skills retain their specialized restrictions.

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
target the separate opaque references. Snapshot page/link URLs omit user info,
query strings and fragments; opaque element references retain their targets.

## 3. Per-turn lifecycle

The browser belongs to one Agent interaction:

```text
Agent turn starts
  -> no browser allocation
  -> first Browser Tool call lazily creates the WebView
  -> Tool loop reads and acts through the five schemas
  -> LLM produces the final response
  -> browser resources are destroyed
  -> selected speech synthesis starts
```

Cleanup runs in `finally` on success, cancellation, timeout, provider failure,
or Tool failure. It must occur before final-reply TTS and on the Android main thread.

Cleanup stops loading, cancels pending callbacks, invalidates snapshots and
element references, removes WebView clients, detaches the view, and calls
`destroy()`. Interaction-version checks reject late navigation, script, file,
permission, renderer, and Tool callbacks.

Cookies and WebStorage use the app's default WebView storage, not isolated
per-account profiles. Destroying the per-turn view flushes cookies without
clearing site storage. Valid sessions are reused without prompting; session
expiry must be established from the page, not a network failure or Cookie count.
This does not import Chrome/external-browser login. Process death does not
preserve the pending Agent call or live WebView, although persistent site data
can remain.

### 3.1 Background execution

Moving Mochi to the background does not pause, cancel, or destroy the active
browser session. The Tool loop and WebView continue until the final model
response, explicit cancellation, timeout, failure, or process termination.

A Scheduled Agent run may create the same WebView runtime without displaying
it. Scheduled runs receive only `browser_navigate`, `browser_read`, and
`browser_scroll`; they cannot click controls or enter page data. A mutex
serializes Browser turns, so a scheduled run waits rather than replacing an
active foreground Browser session.

Ordinary Browser actions continue without approval prompts. When Main requests
assistance, a private generic notification opens Mochi if notifications are
enabled; it contains no site, request text or credentials. Disabled notifications
are logged. No Activity is launched automatically. Process/background execution
is still subject to Android lifecycle limits.

## 4. Home presentation

When Home is the current surface, an active browser session replaces the Home
content with a trusted native `BrowserSessionCard`. The card contains the live
WebView, current origin, loading state, current Agent action, and Stop Agent.

`BrowserSessionCard` is runtime UI owned by `AgentBrowserRuntime`. It
is not a `card_directive`, is never generated by the model, and is not stored in
conversation history.

When the current surface is Talk, Planner, Settings, Skills, or Tools, Mochi
does not show a Browser Card and does not navigate to Home. The current surface
remains unchanged and the existing global pipeline indicator reports Browser
Tool progress. If the app enters the background, execution continues without a
Browser Card and is represented by the Agent notification.

The ordinary Browser Card does not accept touch input. During explicit browser
assistance the same WebView moves into a secure full-screen dialog showing
origin, Agent-generated explanation, and native Continue/Cancel buttons.
No snapshots or Agent actions are allowed while assistance is pending.
User navigation invalidates references. Credentials are entered in the website
or native account editor, never chat. Supported form submissions can offer native-only,
explicitly confirmed local password saving as described below.
Question assistance uses choices or typed text (not voice-answer routing).
The request explanation is spoken once while the Activity is resumed; pausing
or answering stops that speech. Speech failure leaves the visible request.

Ordinary page-based login/verification can use handoff, but unsupported passkey,
external-app, file, permission or popup flows remain unsupported.

## 5. Safety boundary

Page text, accessibility labels, URLs, forms, downloads, and script-produced
content are untrusted evidence. They never become Agent instructions.

The WebView permits public HTTPS navigation only. It blocks cleartext HTTP,
credential-bearing URLs, `file:`, `content:`, `javascript:`, private and local
network targets, unsafe external schemes, mixed content, and SSL or Safe
Browsing bypass.

Browser Tool actions are allowed by default and never request Mochi approval.
Form submission, sending or publishing, account changes, purchase, deletion,
execute without an additional native confirmation. There is no `confirmed` Tool
argument. Authentication does not authorize unrelated actions; Skill-specific
read-only/booking/payment restrictions still apply. JavaScript alerts are
acknowledged; confirm/prompt dialogs are cancelled.

Platform boundaries still apply. Unsupported Android permissions, blocked URL
schemes, Safe Browsing failures, SSL failures, private-network targets, and
external-app launches return typed errors rather than opening an approval flow.

No webpage receives a general Android JavaScript interface. Semantic extraction
is a bounded one-way evaluation returning only nearby Markdown, roles, states,
bounds, safe form metadata and native-generated references. The only native
message channel accepts bounded credential submissions while the user controls
the browser. Native checks require the main frame, HTTPS and exact source/current
origin (including port); the page cannot supply the stored origin or save without
native confirmation. AndroidX document-start script and message-listener support
are required; unsupported WebViews show an update notice.

The fixed observer handles trusted top-level form submissions, recognizable
login-button clicks and Enter in password/code fields, not each keystroke.
Visible phone and verification-code fields identify SMS login. Its native
payload contains only kind and phone number; code/password fields in an SMS
message are rejected. Code fields are excluded from password capture/filling,
including masked `one-time-code` inputs. SMS filling touches only a single
recognized phone field, never requests a code or submits.
Nonstandard AJAX widgets, cross-origin frames, passkeys and external-app
authentication are not universally supported. The save offer does
not prove successful login. Captured/filled passwords remain native-only and
are redacted if echoed into subsequent Agent snapshots during the same turn.
More than 32 distinct protected passwords blocks snapshots until a new turn.

## 6. Tools UI

The Tools surface presents one **Agent Browser** provider card. Browser
configuration is not scattered among unrelated built-in Tools.

The card contains:

1. one provider-level enable switch;
2. one expandable grouped list containing the five Browser Tool switches;
3. **Websites and logins**, always outside the collapsed Tool list;
4. a short notice that Home can show live browsing, other surfaces show Tool
   status, and Browser Tool actions execute without approval prompts.

Disabling the provider removes all Browser Tool schemas from the Agent request.
When enabled, individual Tool switches still determine which schemas are
registered. The five switches remain adjacent and in the same order as the
Tool contract table.

Website management is a secure full-screen dialog with a Bing search/address
field centered beneath the Mochi logo, a top-right Close button, and a separate
translucent website-list panel, not dashboard cards. Its entry remains a filled
button matching Connect Feishu. Opening a website row shows a secure second
list containing one shared Cookie/site-data entry, when detected, plus its
password/SMS accounts. Blue data and purple credential badges include text labels.
Credential swipes reveal Edit/Delete; data swipes reveal only Delete. A shared
fraction-based reveal clamps every row at half width, including after resizing
or repeated gestures; swiping never deletes. Search or opening an account moves
to a full-screen manual browser sharing Mochi's cookie store, with four bottom
controls: Back, Forward, Home and Sites. Home hides but retains the same
WebView, history and form state; Return to page reattaches it. Sites opens
the homepage's shared website-list component in a secure bottom sheet.
Selecting an account matching the current exact origin explicitly fills its
login form without submission; other origins navigate to that account's site.
The homepage Close action ends the manual session. Manual browsing retains
exclusive Browser ownership even while Home or the account sheet is visible;
wake listening pauses and no Agent snapshots/actions run. Closing restores Tools.
Further manual navigation keeps that WebView's selected mode, avoiding a UA
change on an already interacted-with form.

Website groups combine up to 100 known visited hosts and up to 100 encrypted
accounts. Native inspection checks HTTPS root-URL Cookie presence for those
hosts and their registrable domains, plus matching WebStorage origin metadata.
Only domains/data-presence flags leave the runtime; Cookie values, names and
storage contents never enter the UI catalog or model context. Inspection is
bounded to five seconds and failures are shown explicitly. It is not exhaustive
Cookie enumeration: unrecorded hosts, path-only cookies and cache-only sites may
not be discoverable. No network request or login inference is used.
Domains with detected data or saved accounts appear automatically; ordinary visits
without either are omitted. OkHttp's bundled public-suffix list groups registrable
domains, including private suffix boundaries. Accounts retain their exact origins
and revisions: grouping neither merges identities nor broadens filling authority.

Native confirmation saves exact HTTPS origin, username and password for password
accounts, or only origin and phone for SMS accounts, in a separate no-backup
Keystore-encrypted catalog. Account identity includes login kind, so an SMS login
never overwrites a password account with the same username. Legacy records with
no kind remain password accounts. SMS records require an empty password.
No model Tool, conversation, Provider export or notification receives its
secrets. Editing a password account's website requires password re-entry; blank
replacement preserves its password only on the same origin. SMS editing has no
password/code field. Stale edits/deletes fail visibly. Explicit fill requires
the exact main-frame origin and same-origin form action when a form is present.
Password filling requires a form; SMS widgets may be form-less. Neither submits.
Deleting an account preserves cookies; clearing website data preserves accounts.
Multiple stored accounts do not imply isolated browser profiles.

The account menu also offers Add SMS login account for a completed login that
cannot be captured retroactively or an unsupported widget. The user supplies
the website and phone number; Mochi never infers a phone from cookies or claims
that saving guarantees a valid session. Expired SMS logins need a fresh code.
The overflow menu provides separate resource-cache and confirmed all-website-data
clearing. There is no mode selector. The runtime privately retains up to 100
visited hosts/timestamps and last Agent-selected mode in Tool DataStore
`browser.sites.v1`, without queries, credentials or inferred login flags.

Resource-cache deletion preserves login storage. Complete per-site/global
deletion uses feature-gated AndroidX `WebStorageCompat` APIs, removing cookies,
cache and JS-readable storage. Per-site deletion affects the registrable parent
domain and subdomains. The nested site-data row offers confirmed per-site deletion, disclosing the
parent/subdomain scope and possible sign-out while preserving saved credentials.
During manual browsing confirmation also warns that the current page closes;
the ViewModel cancels and joins its manual job before calling serialized deletion.
The operation outlives its dismissed browser sheet and reports feedback on the
homepage. Agent-owned turns are not cancelled and still block deletion.
The overflow menu retains confirmed all-site clearing.
Unsupported WebViews require an update, never a partial fake logout. Deletion
is serialized against Browser turns and awaits completion; a delayed callback
reports failure and blocks new Browser use until deletion actually finishes.
Local deletion does not delete remote accounts or revoke remote authorization.

## 7. Failure behavior

- Stale references return `STALE_REF` and require a fresh snapshot.
- Blocked navigation returns `NAVIGATION_BLOCKED`.
- Safe Browsing and SSL failures stop navigation without an Agent bypass.
- Renderer termination destroys the WebView and returns `RENDERER_GONE`;
  Mochi never replays a form submission automatically.
- Human login/CAPTCHA can trigger Agent-requested assistance; unsupported system
  permissions and external authentication flows do not gain a bypass.
- Timeouts and cancellation close the per-turn browser session.
- Tool results remain bounded and never include cookies, credentials, request
  headers, full form values, or unrestricted page source.

## 8. Delivery

The implementation uses one per-turn WebView and the five Tools above.
Tabs, downloads/uploads, structured extraction, isolated named accounts,
raw-password storage, process-death task resumption, and visual
fallback are later capabilities and require separate product and security
review.
