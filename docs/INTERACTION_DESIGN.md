# Native Android Interaction Design

## 1. Surfaces

| Surface | Purpose |
| --- | --- |
| Face | Default Mochi expression and voice state |
| Conversation | Current and historical text conversation |
| Planner | One bottom destination with Today and Calendar sections |
| Settings | BYOK, voice, permissions, backup, and tools |
| Skills | Bottom-navigation destination for built-ins and skills.sh |

Mochi remains visible as a compact companion where space permits, but planner
content takes priority when the selected surface requires reading or editing.

## 2. Voice navigation

Voice navigation is semantic and structured. UI code must not scan final reply
text for keywords.

| Intent | Surface |
| --- | --- |
| Current time/date | Home date-time presentation |
| Current weather/temperature/humidity | Home weather presentation |
| Current position or nearby request | Keep current surface; request location permission if needed |
| Today's schedule/todos | Today's Day Planner |
| Another date | Calendar Day for resolved date |
| Todos on another date | Calendar Day with todo section |
| Todo without a supplied date | Today's Day Planner with a default-date notice |
| Create/update item | Relevant surface with item highlight |
| Generic calendar knowledge | Keep current surface |

Examples:

- “现在几点？” -> Home date-time presentation.
- “现在温度和湿度怎么样？” -> Home weather presentation.
- “明天星期几？” -> Calendar Day for tomorrow.
- “下周三有什么安排？” -> Calendar Day for resolved Wednesday.
- “提醒我周六买牛奶。” -> create dated todo, open Saturday, highlight it.
- “我还有什么没完成？” -> Todo filtered to active.
- “公历和农历有什么区别？” -> no automatic navigation.

## 3. Transition timing

1. Keep the current surface while STT is uncertain.
2. Show listening/thinking state immediately.
3. Execute required data tools.
4. Apply the validated UI directive before TTS begins.
5. Animate to the target in 200-350 ms.
6. Highlight changed items for approximately 1.5 seconds.

If a directive is invalid, keep the current surface and still deliver the
spoken response. Navigation failure must not fail the whole conversation.

The current Conversation surface exposes push-to-talk, partial transcript,
stop, and Agent cancellation controls. It uses a compact Mochi identity header,
provider-readiness status, a guided empty state, labeled asymmetric message
bubbles, a small local send date/time beside each **Mochi / You** label,
automatic scrolling to the newest message, and a raised rounded composer that
keeps text and voice actions distinct. Restored history and Scheduled Agent
results retain their persisted timestamps. Errors and partial speech appear as
separate status cards rather than conversation messages. The validated
directive is applied before the accepted Agent reply is sent to speech synthesis.
Provider failures use localized, actionable status messages distinguishing
timeout, connectivity, access denial, rate/quota limits, service availability,
configuration, and response errors. Raw provider error text is not displayed.
These messages do not claim that device actions were skipped or rolled back:
an earlier Tool may already have executed before a later model request failed.

The native `HI MOCHI` foreground wake service is enabled by default once the
required Android permissions are granted. Settings can disable it, and that
explicit choice is retained across later launches.
When Mochi is visible, wake and media triggers start listening without changing
the current surface.
When it is backgrounded or locked, wake detection posts a lock-screen-visible
notification; tapping it starts the same permission-checked voice path.
After a confirmed “Hi Mochi” wake, Mochi speaks a one-syllable acknowledgement
("嗯？" in Chinese, "Yes?" in English) before listening. The acknowledgement
is transient feedback, not a conversation message, and is excluded from
history and model context. Microphone-button and media-button starts do not add
this acknowledgement.

An app-wide compact pipeline card appears above the active surface during
Listening, Skilling, Thinking, Tool, Summarizing, and Speaking stages. It uses
a breathing activity pulse, concise status copy, and a six-segment progress
track so stage changes remain legible without relying on color alone.

On the Face surface, Mochi is a softly lit character with a layered halo,
breathing motion, highlights, blush, and animated eyes and mouth. Each pipeline
stage has a distinct expression and accent, while the current stage is also
shown as text beneath the character. Home actions and nonessential helper copy
are hidden while the pipeline is active so the character and current state
remain the focus.

Current time/date requests transform the full Home content area into a live
clock and date presentation. Current weather requests use the same full-area
layout for temperature, apparent temperature, humidity, and weather state.
Face and information modes transition with coordinated scale/fade motion, and
a compact Mochi remains available as the restore control. Home stays selected
in bottom navigation throughout.

Home also offers Focus mode for the Face, date-time, and weather presentations.
Focus hides app navigation and Android system bars, keeps the display awake,
and fills the screen with the active Home presentation. The **Focus mode**
control in that presentation remains available and toggles Focus off when
tapped again. A separate top-right **Exit focus** action exits immediately.
Back and navigation away from Home also exit. Focus survives device rotation,
and date-time and weather switch to two-column landscape layouts so their
primary facts and supporting metrics remain balanced and unclipped.

When enabled, an idle Focus presentation enters low-power standby after 30
seconds by default. Settings places this low-priority option last and offers
30-second, 1-minute, 2-minute, 5-minute, and 10-minute delays. Standby never
starts while voice recognition, the Agent pipeline, or Agent Browser is active.
It replaces every Home presentation with the same pure-black display: a
minimal low-contrast Mochi, small localized date, and large system-format time
without seconds. Portrait stacks Mochi, date, and time vertically; landscape
places Mochi beside the date/time column. The content shifts among bounded
positions once per minute to reduce OLED burn-in.

Standby keeps the screen and wake-word pipeline active but lowers only Mochi's
window brightness. Touch, listening, or any Agent pipeline activity restores
the exact prior Home presentation and restarts the idle timer. Exiting Focus,
leaving Home, backgrounding, or destroying the Activity restores the original
window brightness.

The visual transition never replaces the spoken response. Mochi's reply must
explicitly contain the displayed local date/time or weather facts, and
The selected speech-output path reads that same reply.

Trusted generated cards use one data model and Action renderer in both Home and
Talk. On each message, a valid card replaces only that message's duplicate
assistant bubble; messages without cards retain their normal text bubbles. Card
reply text remains available for speech, history, and fallback. Home cards
participate in the same morph and Focus behavior. Settings and Skills are
protected from automatic card navigation; cards requested there are deferred to
the conversation message. Weather, calendar, and todo cards remain typed;
external web and MCP results use the bounded general content card when the
model includes a `card_directive`.

If weather retrieval succeeds but the remaining Agent turn fails, Mochi uses
the structured weather result to produce a deterministic spoken summary, then
continues the voice-originated Listening loop after TTS.

For a voice-originated turn, successful TTS completion immediately opens one
follow-up `SpeechRecognizer` listening window. Recognized speech starts another
turn without a wake phrase. A no-result or speech timeout ends continuous
conversation, returns the pipeline to idle, and resumes wake-word capture.
Text-originated turns do not open the microphone automatically.

Wake-word capture is active during Agent work, tool execution, summarization,
and TTS. Saying "Hi Mochi" during any of those phases interrupts the current
interaction and opens a fresh listening window. It is paused only while the
full-sentence STT path owns the microphone. Completed tool side effects are not
rolled back; stale replies, cards, navigation, and TTS callbacks are ignored.

## 4. Manual navigation

The Tools surface has a stable **AgentLink** provider card even before connection.
It reuses the same rounded surface, spacing, localized typography, and Tool rows
as other providers. The three Tools start collapsed behind **Show tools (3)**;
each expanded row shows a localized name and description plus its technical ID.
Action buttons wrap on narrow screens without hiding authorization or chat actions.
Tap **Connect/Open AgentLink**, confirm the scoped native authorization page,
then return through its Activity result. Cancellation does not connect.
Enable the provider and desired workspace/chat/control switches, then enable
the AgentLink Skill. Refresh rechecks current authorization/Bridge availability;
disconnected and incompatible states are not displayed as cached-live.
**Manage access** and **Revoke** remain explicit user actions.

Successful shared-chat reads and control results retain non-secret linked-chat
buttons in this provider card. Tapping one opens the trusted AgentLink native
chat, never a model URL. These links are not live task monitors: use tools to
read remote changes and current task state. Closing Mochi does not stop remote
tasks. A human override conflict stops Mochi automatic follow-ups and requires
new user direction rather than silently retrying or overwriting the human.

Voice does not replace touch.

- Face -> swipe/tap to Planner or Conversation.
- Planner -> switch between Today and Calendar without changing tabs.
- Month -> tap a date for Calendar Day.
- Day -> return to its containing month or move between adjacent dates.
- System back follows Android predictive-back behavior.

All paths update the same `MochiSurface` state used by voice.

Settings is always available from the top-right app action. If no provider is
configured, first launch opens a guided connection screen before normal use.
The provider choices are Azure OpenAI, OpenAI, and custom OpenAI-compatible.
Azure OpenAI explains that the model field is the deployment name and exposes
the API version separately. Saving a blank API-key replacement preserves the
existing encrypted key.

**Speech recognition and synthesis** retains Android as the zero-configuration
default. Selecting iFlytek or Azure exposes a default-off **Also use this provider
for speech synthesis** switch. It reuses the existing encrypted credentials;
blank secret fields still preserve them. The adjacent disclosure explains that
assistant reply text leaves the device when enabled. Cloud voice selection appears
while enabled; the Android offline voice picker is available for the system Provider.
This is one shared Provider selection, not independent STT and TTS accounts.

Voice selection uses a common bottom sheet with **Follow app default**, named voice
rows, single-choice selection, and explicit preview buttons. iFlytek offers the five
basic presets (`x4_xiaoyan`, `x4_yezi`, `aisjiuxu`, `aisjinger`, `aisbabyxu`). Azure
loads its region's supported voices using the saved credentials; Azure and Android
support search, the app-language filter, all languages, and explicit refresh.
Android lists only installed non-network voices and links to system TTS settings.
Cloud Providers retain an advanced custom-ID field, including previously saved IDs
that are not in the catalog. Listing does not imply account authorization or free
usage; only a completed preview earns a transient **Last preview succeeded** label.

Confirming the sheet changes the draft only; **Save speech settings** applies it.
Each Provider remembers its own voice, including when other Providers are saved.
Android voice names are device-local and are not copied in Provider shares.
Connection edits (Provider, endpoint, AppID, or replacement secrets) must be saved
before preview; voice-only edits can be previewed without saving. Preview sends
only a fixed localized greeting, consumes cloud quota when applicable, never calls
the LLM or writes history, and leaves the saved voice unchanged. It cannot interrupt
an active conversation. During preview, pause wake and restore its prior enabled
listening state afterward. Switching voices, stopping, dismissing the sheet, leaving
Settings, or saving/importing connections cancels preview; stale callbacks cannot
mark newer previews successful. Synthesizing and playing have distinct cancellable
button states. Errors include safe provider/HTTP codes where available and never
silently select a different voice.

Wake acknowledgement always requests a local Android voice; a missing local voice
does not prevent listening. Cloud synthesis errors remain visible as status, preserve
the already accepted reply, and stop the automatic follow-up listening loop. There is
no silent fallback or automatic retry after partial playback. A new wake or explicit
microphone action can start another turn.

Share Providers always opens a checklist before Android's share sheet. The
configured LLM and speech Providers begin selected; every Tool credential
entry begins unselected on each opening. Available Tool entries are Amap,
Tencent Docs, and each configured manual MCP server. The confirmation screen
warns that possession of the complete link grants access to the selected API
resources. Import replaces only included connections and then enables their
Provider switches and the Tool selections carried by the share. Notion OAuth,
Mi Home sessions, and Android permissions require setup on the receiving
device and never appear in the checklist.
Speech shares include the synthesis opt-in and selected voice. Older v2 speech
shares without these fields import with system speech output; receivers need
an updated app to accept shares carrying the new fields.

Skills sits beside Home, Talk, Planner, and Tools in bottom navigation.
Installed/Explore uses a dark selected segment. Explore opens with the public
skills.sh Trending (24h) leaderboard, then switches to search results after a
query. Cards show rank, source, install count, and a derived popularity label.
Every installed Skill, including read-only built-ins, can be enabled or
disabled. A Skill cannot be enabled until all of its required aggregate Tool
groups are ready; its card names missing provider/group labels such as
**Tencent Docs MCP**, never a list of raw Tool IDs, and keeps the switch off.
A group is ready only when its provider and every member Tool required by that
Skill are ready. A previously enabled Skill whose dependencies become
unavailable remains switchable off but is suspended from Agent discovery.
Existing built-ins default to enabled; the
Notion Knowledge, Tencent Docs Knowledge, and Mi Home Smart Home built-ins
default to disabled.
Each MCP server's detailed Tool list starts collapsed and can be expanded
without disabling the server or changing individual Tool selections. Built-in
knowledge providers enable their core search, listing, and reading Tools on
first connection.
Tools also contains an Amap Maps provider card. It opens the official console,
stores the pasted Web Service Key and optional Security Key encrypted
on-device, and exposes a provider switch. Its connection guidance explicitly
selects the Web Service platform rather than Android, explains that release and
debug SHA1 fingerprints are not required, and distinguishes the optional
digital-signature Security Key from SHA1. Its six map and merchant capabilities
remain individually selectable in an expandable Tool list. The two built-in
Travel Planning and Merchant Discovery Skills provide route, destination
weather, public no-login train or flight research, nearby-search, merchant
detail, rating, average-cost, and missing-review guidance. Travel Planning is
available only in foreground interactions with its Amap and all five Agent
Browser Tools enabled. It stops on authentication, CAPTCHA, identity,
passenger, booking, or payment flows rather than requesting manual takeover or
bypassing the website.
Current Location appears as an independent built-in Tool switch. A clearly
location-dependent Agent request keeps the current surface, continues after
the Android permission result, and returns typed permission, timeout, or
provider errors instead of silently guessing a position.
Agent Browser uses the same provider-card pattern. Its provider-level enable
switch and the five `browser_read`, `browser_navigate`, `browser_click`,
`browser_input`, and `browser_scroll` switches are grouped together in one
expandable card rather than appearing as separate built-in cards.

Tools places its Extensions section after all MCP server cards. The Mi Home
card always occupies one stable position there, follows Mochi's selected
Chinese or English UI language, and moves through these states:

1. **Not installed**: describe the optional unofficial connector, its
   approximate download size, and open the trusted GitHub Release page through
   an **Install extension** action.
2. **Installed, not connected**: show extension version and a **Connect Mi
   Home** action.
3. **Waiting for QR confirmation**: open the extension-owned connection
   activity, show a bounded QR expiry countdown, refresh on expiry, and allow
   cancellation. Copy explains that another phone already signed into Mi Home
   must scan and confirm the code.
4. **Connected, disabled**: show the selected homes and device count without
   registering any Agent Tools. This is the default after connection; all
   child Tool switches start checked so enabling the aggregate provider makes
   the full supported Tool set available.
5. **Connected, enabled**: show one provider switch and one collapsed list of
   individually selectable Mi Home Tools.
6. **Authorization expired**: remove every Mi Home Tool from the Agent
   registry and show **Reconnect**.
7. **Update available**: open the trusted replacement APK. Android performs an
   in-place package update; stored authorization and device selections remain
   unless the extension rejects an incompatible state version.

The optional Termux card uses the same extension section, provider switch and
collapsed individual tools. Configure opens the signed extension's setup
Activity: verify installation, grant Android command permission, then check
the connection. First-time setup exposes a single copied command with paste
instructions; returning from Termux automatically checks once. Already working
connections need no terminal visit. Returning to Mochi refreshes readiness.
**Enable tools and Skill** explicitly enables both tools and the default-off
Termux Skill; a Skill cannot silently enable the provider.

Ordinary shell execution remains in the background without switching apps.
Once the provider and relevant Tools are enabled, foreground conversations,
Scheduled Agents, and Subagents execute commands automatically. There is no
per-command/run approval dialog, voice-confirmation interception, or separate
background Shell switch. The card explains automatic execution and model-visible
output. Android command permission and explicit provider enablement remain.
Disabling the provider or an individual Tool blocks subsequent calls without
stopping submitted commands; old registries remain revoked after re-enabling.

**Termux tasks** provides submitted IDs, explicit refresh, bounded selectable
output, Stop and completed-task cleanup. Reading output here does not call a
model. Closing a dialog or conversation is not process termination. The UI
distinguishes submitted, running, succeeded, failed, stopping, stopped, timed
out and unknown. Detached descendants may outlive termination of the tracked
group; the UI never promises otherwise. Interactive commands require manual
Termux work, not automatic App switching or a silent rerun.

Static package, signing, Service, Activity, and signature-permission checks are
enough to enter **Installed, not connected**. A failed first Binder start must
not relabel that trusted package as untrusted or send the user back to
installation. **Connect Mi Home** explicitly launches the configuration
Activity, allowing Android and OEM background-start controls to start a newly
installed extension that has no launcher entry.

The extension has no launcher activity, so the Android launcher continues to
show only Mochi. Android Settings and the system package installer identify it
as **Mochi Mi Home Extension** so users can inspect or uninstall it.

After QR connection, users select homes and supported devices. Lights,
switches, plugs, fans, air conditioners, air purifiers, humidifiers, curtains,
read-only sensors, televisions, cameras, and scales derive capabilities from
each device's MIoT specification rather than from a fixed model allowlist.
Each selectable device uses a full-width rounded card with a primary device
name, separate home/room and category lines, and a checkbox. The whole card is
clickable; selected cards use a highlighted background and a checked checkbox,
with checkbox semantics so selection never relies on color alone.
Locks, alarms, garage doors, robot-vacuum maps, camera storage mutation, and
unsupported capabilities never appear. Ambiguous duplicate device names are
displayed with home and room labels.

Ordinary use remains voice-first:

- “Turn on the living-room television” may execute an available power action.
- “Turn the television volume down” invokes a bounded television control.
- “Set the bedroom air conditioner to 26 degrees” invokes only the declared
  temperature and mode properties for that selected device.
- “Set the dining-room light to 40 percent” invokes only declared power and
  brightness properties.
- “What is the study temperature?” reads the selected sensor without exposing
  unrelated devices.
- “Show the latest door-camera event” retrieves the newest available event
  image and presents it locally.
- “How much battery does the scale have?” reads only exposed device state.

Television commands report **sent** unless a later state read proves the new
state. Camera setting changes and scene execution require explicit current-turn
intent; ambiguous devices or scenes produce a clarification instead of a
guess.

A successful latest-camera-event request creates a deterministic trusted
Camera Snapshot card. The image remains local and ephemeral, with device,
home/room, event type, and capture time shown from Tool evidence. The card does
not imply that the image is live. It states whether the image is available only
inside the current run, including at most one explicit Subagent handoff, or
remained device-only. Provider settings contain a default-on **Multimodal
input** switch immediately after the conversation-context settings and tell the
user to enable it only for an image-capable model. The setting governs supported
model media input, not camera retrieval itself. The card has Dismiss but no
source, share, or save action. Leaving the card, cancelling the turn, process
death, or attachment expiry releases the image.

When a Browser Tool runs while Home is selected, Home transforms into a trusted
`BrowserSessionCard` containing the live WebView, origin, loading state, current
Agent action, and Stop Agent. This runtime card is not a
model `card_directive` and is not persisted.

When Talk, Planner, Settings, Skills, or Tools is selected, Browser Tools keep
the current surface unchanged. No Browser Card or automatic Home navigation is
shown; the existing global pipeline indicator reports Tool progress. The
browser closes after the final model response and before TTS, and also closes
on cancellation, timeout, or failure.

Moving Mochi to the background does not pause Browser Tools. A foreground Agent
notification shows that browsing is active and provides Stop. Browser actions
continue without approval prompts or waits.
Selecting any card opens a rendered Markdown preview; market skills can switch
between Preview and Edit after installation. The reader hides YAML frontmatter
and renders headings, paragraphs, ordered/unordered lists, quotes, fenced code,
inline emphasis/code/links, dividers, and horizontally scrollable tables.

Option controls use filled color only for the selected value and an outlined
transparent style for unselected values. Bottom navigation is the exception:
the selected destination is filled while unselected destinations remain plain
without individual borders.

## 5. Calendar day composition

```text
Date header
Mochi compact state
All-day events
Timed agenda
Dated todos
Add action
```

Events and todos remain distinct domain objects but share the day timeline.
Overdue todos are visible without being silently moved to today.
Active todos are listed before completed todos. New todos always receive an
explicit date; when no date is supplied, the current local date is used and the
user is told about the default.

Today also includes active todos scheduled on earlier dates. These items retain
their original scheduled date and are labeled as carried over; completed and
future-dated todos are not carried into Today.

## 6. Accessibility and localization

- Support Chinese, Japanese, and English date/relative-time parsing.
- Follow the system language by default: Chinese locales use Chinese and all
  other locales use English.
- Settings provides explicit Follow system, Chinese, and English choices.
- Render app labels, notifications, spoken summaries, and Calendar headings in
  the selected UI language.
- Localize **Agent Browser** as **智能体浏览器** in Chinese, including Tool
  cards, accessibility labels, and Skill prerequisite messages. English Agent
  instructions and Tool identifiers remain unchanged.
- Use system 12/24-hour preference.
- Provide content descriptions and scalable text.
- Never communicate state through color alone.
- TTS response and visual transition must describe the same resolved date.
- Keep default SOUL, USER, AGENTS, Tool schemas, and model system prompts in
  English; UI localization must not rewrite Agent instructions.

## 7. Unified extension experience

**Implementation status:** the shared shell, Termux/Mi Home setup, Mochi-owned
integration cards, and explicit enablement are implemented. AgentLink's external
native pages and full real-device/usability acceptance remain outstanding.
This section defines the common contract, including remaining acceptance targets.
Shared visual tokens, measurable layout/accessibility
targets, and acceptance criteria live in
[`android/extensions/README.md`](../android/extensions/README.md).

### Common structure and trust boundary

Use a shared Mochi connection wizard rather than a page of setup commands and
equally prominent buttons. The structure is **Prepare > Authorize > Verify**,
with provider-specific content and a final, explicit host enablement action.
Only the current incomplete step is expanded. Users can leave and continue;
already verified prerequisites are not presented as work to repeat.

Connection, user authorization, Tool enablement, and Skill enablement remain
separate facts. Show **Connected - not enabled** when appropriate, not a
premature ready-to-use success. The completion summary names the capabilities
about to be enabled. **Enable tools and Skill** is an explicit host action;
**Not now** preserves the connection without enabling execution. Preserve
existing individual choices when reconnecting. If a required Tool is disabled,
explain the Skill dependency and ask before restoring that Tool; never silently
overwrite a user's selection.

Every Mochi integration card uses the same structure and state vocabulary.
The protocol does not change: Termux/Mi Home are same-signer extension APKs;
AgentLink is a separately authorized companion. Its native authorization and
access-management pages require a coordinated change in
[`android-agent-link`](https://github.com/gongpx20069/android-agent-link).
This repository owns the Mochi card, preflight, handoff, return, and status
presentation, not Android or third-party screens.

State labels distinguish **Not installed**, **Setup required**, **Awaiting
authorization**, **Checking**, **Connected - not enabled**, **Ready**,
**Authorization expired**, **Unavailable**, and **Outcome unknown**.
Do not map every transport failure to expired credentials or reinstall.
Readiness comes from a fresh validated result, never a timer, app return, or
the user's statement that a step is done.

### Termux: guide the user through the necessary work only

The setup page presents the following evidence-driven sequence instead of a
flat list of permission, command, and testing buttons:

| Step | Display and primary action | Advance condition |
| --- | --- | --- |
| Prepare | Detect Termux installation. If missing, **Install Termux** opens official installation guidance. Explain first-run initialization and how to return. | Package availability is verified; initialization is not assumed from installation alone. |
| Authorize: Android | Explain that Mochi Termux Extension needs Android's permission to run commands in Termux. **Allow command access** opens the system permission request. Skip when already granted. | Actual Android permission is granted. A denial stays on this step; open app settings only after another explicit action. |
| Authorize: Termux | After permission, a user-initiated connection attempt checks whether setup already works. Otherwise guide the one-time external-access setup when evidence supports that recovery. **Copy command and open Termux** is the only primary action. | Returning from the pending terminal handoff starts one verification attempt; neither copying nor returning counts as success. |
| Verify | Show actual phases: checking access, installing/updating the bundled helper, checking shell prerequisites, and receiving a callback. | Validated callback and required shell checks succeed. |
| Finish in Mochi | Show **Connected - not enabled**, capability/privacy summary, **Enable tools and Skill**, and **Not now**. Return via the validated native Activity path; no separate Done/Refresh/Test sequence. | Host revalidates connection and applies only the enablement explicitly chosen by the user. |

The first Connect action explains that verification may write Mochi's bundled
helper into Termux private storage; it does not execute a user task or call a
model. Fully configured users bypass the manual-command branch. A saved
connected flag alone is insufficient to skip fresh checks.

For the terminal handoff, show concrete instructions before leaving:
**Wait for Termux initialization if needed. Long-press the prompt, paste the
copied command, press Enter, then return to Mochi.** Show what the command
changes and make its complete text inspectable/selectable in an expandable
code panel. Copy the exact inspected command; no remote bootstrap download,
invisible auto-paste, accessibility clicks, or simulated Enter.

Stock Termux does not let another app change its private
`allow-external-apps` setting before access is authorized. Therefore a fresh
setup still needs this one manual command; do not promise zero-touch setup.
Explain that this setting permits requests from externally authorized apps,
not only Mochi, and that Android permission and Mochi authorization are
additional checks. Preserve other Termux settings and installed data.

On return, use the pending attempt identity to resume once. Rotation, repeated
onResume, late callbacks, or cancellation must not start duplicate probes or
install helpers concurrently. Losing the Activity can require a new check,
never a silent replay of the bootstrap command or a previously submitted task.

| Observed problem | User-facing recovery |
| --- | --- |
| Termux absent | Install from official guidance; do not show permission/test buttons yet. |
| Android command permission denied | Explain which permission was denied; offer retry or, when necessary, an explicit Open settings action. |
| Explicit external-access rejection | Return to the one-time command step with its purpose and paste instructions. |
| Shell executable/helper prerequisite error | Explain initialization or the named missing prerequisite; do not run package installation without consent. |
| Callback deadline expired | Say the connection was not confirmed. Offer Retry and collapsed checks for terminal setup/background restrictions; do not assert which setting is wrong. |
| Android reports background start blocked | Offer Open Termux, then retry after return; do not require disabling battery optimization for every user. |
| Protocol/package trust mismatch | Explain compatible in-place update/signing requirements; never suggest uninstalling as the default repair. |

Use typed, localized check-stage/error evidence, not string matching on
untrusted shell output. Generic missing evidence stays unknown. Do not show
raw provider payloads, credentials, or arbitrary command output in diagnostics.

There is no separate background Shell authorization step. Connection alone
does not enable the provider. Once enabled, all three execution scopes run
commands automatically; Skill enablement never overrides provider/Tool
switches. Stop semantics remain unchanged.

### Mi Home: the same shell, with QR and selection content

Prepare explains **another phone already signed into Mi Home must scan and
confirm** before showing a QR code. Do not promise an unsupported same-phone
login shortcut or ask for account passwords.

Authorize shows one QR card, clear scan/confirm state, actual expiry countdown,
and a refresh action only when appropriate. Keep one active challenge; Cancel,
expiry, and stale callbacks cannot complete a different challenge. After
authorization, discover the region and devices with visible progress.

The selection step groups supported devices by home/room and offers search,
per-home selection, and a persistent selected count. Only supported visible
devices are selectable; bulk selection never silently includes unsupported
devices. Preserve saved choices on re-entry and retain unsaved choices across
rotation; leaving with unsaved changes asks whether to discard them.
Zero supported devices is an explained empty state, not an enabled success.
Saving an intentional empty selection disables usable device access rather
than inventing a device or failing silently.

Save returns to the common Mochi completion/enablement summary. Manage devices,
reconnect, and disconnect share the common management area. Disconnect requires
confirmation; it must not sit beside Save as an equally prominent primary
action. Camera descriptions still say latest available event, not live video.

### AgentLink: distinguish app authorization from computer availability

Prepare checks installation and protocol compatibility independently. A
missing app links to AgentLink downloads; an incompatible version offers an
update. Explain that remote work also needs a connected desktop Bridge, with
a link to its official setup instructions rather than an unexplained failure.

Authorize names the destination before opening AgentLink's native scoped
consent page. Preserve nonce/signer validation and exact granted scope. On a
validated return, refresh once without requiring a manual Refresh click.
Cancellation or a stale Activity result never grants access.

Verify checks companion authorization and connectivity; authorized read-only
machine/workspace discovery may present available targets. Do not send an
Agent prompt, create a workspace, change configuration, or broaden access as a
connection test. If evidence specifically shows a Bridge is offline, say
**Authorized - computer offline** and offer to open AgentLink/setup guidance.
If the failure source is unknown, say so rather than diagnosing the Bridge.
Do not force repeated authorization to repair mere transport unavailability.

Completion follows the same host enablement summary. Move linked chats to a
separate expandable management section with freshness/unknown-outcome labels;
cached links are not live task state. Access management and revoke stay
explicit, discoverable native actions. Revoke reports unconfirmed remote
results honestly; neither leaving the wizard nor closing Mochi stops remote
tasks. AgentLink remains unavailable to Scheduled Agents and Subagents.
