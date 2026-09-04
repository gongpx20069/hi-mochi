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
directive is applied before the accepted Agent reply is sent to TextToSpeech.

The native `HI MOCHI` foreground wake service is enabled by default once the
required Android permissions are granted. Settings can disable it, and that
explicit choice is retained across later launches.
When Mochi is visible, wake and media triggers start listening without changing
the current surface.
When it is backgrounded or locked, wake detection posts a lock-screen-visible
notification; tapping it starts the same permission-checked voice path.

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
and fills the screen with the active Home presentation until the user taps
Exit focus, presses Back, or navigates away from Home. Focus survives device
rotation, and date-time and weather switch to two-column landscape layouts so
their primary facts and supporting metrics remain balanced and unclipped.

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
TextToSpeech reads that same reply.

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
clickable; selected cards use both a highlighted background and stronger
outline so selection never relies on the checkbox or color alone.
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
remained device-only. Provider settings contain a default-off **Camera image
input** switch and tell the user to enable it only
for an image-capable model. The card has Dismiss but no source, share, or save
action. Leaving the card, cancelling the turn, process death, or attachment
expiry releases the image.

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
- Use system 12/24-hour preference.
- Provide content descriptions and scalable text.
- Never communicate state through color alone.
- TTS response and visual transition must describe the same resolved date.
- Keep default SOUL, USER, AGENTS, Tool schemas, and model system prompts in
  English; UI localization must not rewrite Agent instructions.
