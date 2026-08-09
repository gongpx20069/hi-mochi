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
disabled. Existing built-ins default to enabled; the Notion Knowledge built-in
and Tencent Docs Knowledge built-in default to disabled and guide search,
reading, creation, and updates through their configured MCP tools.
Each MCP server's detailed Tool list starts collapsed and can be expanded
without disabling the server or changing individual Tool selections. Built-in
knowledge providers enable their core search, listing, and reading Tools on
first connection.
Tools also contains a Baidu Map Agent Plan provider card. It opens the official
Service Key page, stores the pasted key encrypted on-device, and exposes a
provider switch. Its five capabilities remain individually selectable in the
built-in Tool list.
Agent Browser uses the same provider-card pattern. Its provider-level enable
switch and the five `browser_read`, `browser_navigate`, `browser_click`,
`browser_input`, and `browser_scroll` switches are grouped together in one
expandable card rather than appearing as separate built-in cards.

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
