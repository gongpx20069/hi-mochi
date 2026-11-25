# Website Development

This documentation applies only to the official Mochi website. Product and
Android application documentation remains in the repository-level `docs/`
directory.

## Structure

```text
website/
├── index.html       English homepage
├── zh-CN/           Simplified Chinese homepage
├── wiki/            English user guides (Chinese in zh-CN/wiki/)
├── agentlink/       Official AgentLink site (Chinese in zh-CN/agentlink/)
├── assets/          Favicon and social sharing image
├── docs/            Website-only documentation
├── styles.css       Shared responsive styles
├── scripts.js       Shared progressive interactions
├── 404.html         GitHub Pages error page
├── robots.txt
└── sitemap.xml
```

## Local preview

From the repository root:

```powershell
Set-Location website
python -m http.server 8000
```

Open:

- English: `http://127.0.0.1:8000/`
- Simplified Chinese: `http://127.0.0.1:8000/zh-CN/`
- AgentLink: `http://127.0.0.1:8000/agentlink/` and
  `http://127.0.0.1:8000/zh-CN/agentlink/`
- Wiki: `http://127.0.0.1:8000/wiki/` and
  `http://127.0.0.1:8000/zh-CN/wiki/`

Keep internal assets relative so both languages work under the `/hi-mochi/`
GitHub Pages project path. The fixed `/hi-mochi/` paths in `404.html` are
intentional because GitHub Pages serves that document for arbitrary missing
URLs.

## Homepage and user guides

Mochi's primary navigation has only Home and Wiki, plus a separate language
switch. Keep it visible even on mobile, without a hamburger menu.
All Mochi headers also have a right-aligned GitHub logo/Star link outside the
primary navigation. It opens the repository in a new tab, with a localized
accessible label; narrow screens show only the logo. Bundle the Octicons logo
locally with its MIT notice, without third-party widgets or star-count requests.
Quick start, model setup and speech setup belong under Wiki, not in the primary navigation.
Both homepages are concise product introductions: a hero, six linked capability
cards, four optional extension cards and a brief privacy note. Cards lead to
the relevant Wiki page or section. Do not duplicate setup steps or the Wiki
directory on the homepage. They do not contain the operating manual or release history. The
English quick start recommends Groq's free API tier; Chinese recommends
ModelScope's free API allowance, without promising unlimited service.

The bilingual Wiki has five destinations: its index, `quick-start/`, `models/`,
`speech/` and `capabilities/`. Keep matched language-switch destinations,
canonical/hreflang metadata and sitemap entries for every page. Use static
HTML, shared styles, native disclosures, side navigation and short steps; no
new runtime, build dependency, account or API key is needed to read a guide.
The Wiki index's common questions include a linked native disclosure explaining
LLMs versus Agents, with a concrete example and the authorized-tool boundary.
The model guide covers all ten presets with three steps each. Put the language's
recommended provider (Chinese: ModelScope; English: Groq) in the first content
section, with always-visible steps rather than a disclosure. The Wiki index
also names that recommendation. Other providers start collapsed. ModelScope has only signup,
Token and model-library links, without binding/credit tutorials.
Speech covers Android, iFlytek and Azure, including saving credentials before
voice previews and explicitly selecting a saved speech account.
The quick start separates LLM setup from an explicit optional speech-model step,
with an on-page navigation link. Recommend iFlytek, retain Android speech without
extra credentials, and link directly to provider setup and cloud voice selection.
It also contains permission, update and configuration-check recovery.
All four guides share the same `wiki-frame`, `wiki-sidebar`, `wiki-article`,
`wiki-section` and `wiki-disclosure` layout. Skills and extensions use a
single-column reference with collapsed capability/setup entries, not homepage
feature cards, illustrations or reveal animations. Preserve existing topic
anchors so homepage links open the relevant disclosure directly.
Quick start provides a direct recommended APK download and a disclosure
for all five architectures, not a link back to a homepage card. Its checked-in
release bundle must match the homepage in both languages.

Preserve named AI and speech accounts, document collaboration
with Notion/Tencent Docs/Feishu, and task-center guidance. Keep onboarding and
connection-switching semantics aligned with the shipped app. Ordinary
foreground chat is not a task-center item. Feishu requires guided self-built-app
setup and authorization; do not present it as one-click login or claim support
for whole-document deletion, native spreadsheets, Bitable, or PPT editing.
Sharing copy must warn that a full link grants use of the included credentials.
The memory feature distinguishes automatic conversation context, optional daily
journals, and journal-guided explicit recall of original conversations. Journals
are date clues, not quotations or proof that an event did not occur. Explain
the default-off shared journal/recall setting without implying that it disables
ordinary conversation context. Storage and indexes are local, but relevant
excerpts may reach the selected model provider. Keep the architecture and
control disclosure accessible with a keyboard and without JavaScript.
The smart-home feature describes the optional signed Mi Home
extension, its supported device categories, and foreground-only latest camera
event images. It must not imply support for camera live view, playback, PTZ,
two-way audio, locks, alarms, body-composition measurements, or sharing Xiaomi
session credentials.

Wake copy follows the shipped language setting: Chinese systems select
“老莫老莫”; English and other systems select “Hi Mochi”. Users may override
the choice, with one active keyword at a time. Browser copy describes visible
text/statistics, separate interactive controls, local login reuse and user
handoff without promising universal site compatibility or verification bypass.
Saved passwords remain local; page content used by the Agent reaches its model.
Keep Responses/Chat Completions guidance in the model guide and
configuration-check guidance in the quick start, including its
possible model-request cost. Skill Market does not auto-enable dependent Tools.
REST copy must distinguish its public HTTPS JSON scope from MCP support.

The App Control feature is built into Mochi, not an extension or a music-only
integration. Describe user-selected apps, supported native controls, explicit
safe popup dismissal and returning with the previous page/Focus mode. Keep
manual accessibility setup, optional vendor background-launch permission and
separate media-session authorization clear. App Assistant defaults on without
granting Tools. Music is an illustrative request; end-to-end exact-song playback
remains unverified. Do not promise universal compatibility or unattended control.
Disclose model-provider access to filtered UI text. Both languages use a native
setup disclosure that remains usable without JavaScript.

## Capabilities and AgentLink

The homepage groups optional capabilities under `#extensions`: Mi Home and
Termux are released same-signer extension APKs; WebDAV Files is a downloadable
extension preview; AgentLink is an independent Android app with a computer-side
Bridge. Keep all four discoverable through compact homepage cards and Wiki
disclosures, using installation links only for released packages. Show benefits first,
then setup and permissions; basic Mochi use does not require any of them.
Mi Home setup and Termux setup use native HTML disclosures, not JavaScript.
Termux copy must disclose automatic command execution after enablement, the
absence of a sandbox, and model-provider access to output used by the Agent.

WebDAV's homepage preview card uses `data-release-status="released-preview"` and
links to its matching release APK and Wiki setup guide. In that guide, disclose pending real-service validation,
read-only defaults, Skill readiness gating, foreground-only access, valid HTTPS
and explicit LAN authorization. It is not Obsidian synchronization or vault
decryption. File text analyzed by the Agent goes to the configured model.
Homepage extension cards use four desktop columns, two tablet columns and one
mobile column. Wiki extensions use the same single-column disclosures as other
guides; retain readable expanded instructions in both languages.

AgentLink's official website lives at `agentlink/` and `zh-CN/agentlink/`.
Treat these as a standalone product destination, not a Mochi extension detail:
lead with phone/browser/terminal continuity, Android download and computer setup.
Follow with five logo-led agent cards, four everyday feature cards (including
single-image input), progressive setup instructions, connection/data boundaries,
and FAQ. Keep optional Mochi integration inside the FAQ rather than a competing
product pitch. Keep both languages equivalent. Agent compatibility/recovery
details belong in a native disclosure under the cards, not in every card.
The navigation order matches the reading order: agents, features, setup, FAQ.
Its product/setup claims follow the
[AgentLink README](https://github.com/gongpx20069/android-agent-link) and
[user guide](https://github.com/gongpx20069/android-agent-link/blob/master/docs/user-guide.md).
AgentLink uses the `master` branch, not this repository's `main`; retain the
correct branch in its documentation links.
Distinguish standalone phone/terminal chat from optional scoped Mochi control.
The Web feature follows the updated AgentLink Bridge source: stdlib `start`
includes the loopback workspace by default, with `--no-web` as the opt-out.
Keep a keyboard-accessible `#web-workspace` setup disclosure in both languages.
Explain the printed local URL/access code, optional workspace-root registration,
local/VM labels and manual drag order (not status priority), and isolated offline
computers. Browser traffic goes through the local gateway; each VM runs its own
updated Bridge. Never present the public website as the Web control endpoint or
encourage tunneling the Web port. Disclose owner-private but unencrypted-at-rest
remote credential storage, retained shared-history/image-cache
limits, and no automatic retry of uncertain sends. APK updates do not update the
Bridge. Web supports Markdown tables/links and one pasted/selected PNG/JPEG up to
5 MiB / 16 million pixels, uploaded only on Send to an image-capable session.
Do not conflate Web's uncompressed uploads with Android's 1 MiB compression.
New sessions may need a text message before image capability is available. Deploy these
instructions in step with the corresponding Bridge source.
Link to AgentLink's own Releases (which include previews), never Mochi APKs or
an assumed stable `releases/latest` endpoint. Explain the running-computer
requirement, authenticated pairing, background limits, and permission boundary.
The connection diagrams are illustrations, not screenshots or live task status.
Use the existing AgentLink mark, mint-accented styles scoped to `.agentlink-page`
or `al-` classes, and native `details` for commands and FAQ. No runtime dependency
or release API is needed on these pages. Keep setup readable without JavaScript.
Agent cards must cover Copilot, Claude, Kimi, Qwen, and DeepSeek Harness, with
guide links rather than independently maintained version pins. Claude uses
`claude-agent-acp`, not `claude --acp`. Disclose DSH's context-only recovery and
separate APK/Bridge upgrades. Do not equate executable discovery with working
authentication or claim a fix for a reported picker discrepancy.

Agent logos are local, unmodified LobeHub Icons SVGs under `assets/agents/`;
that directory includes the pinned upstream revision, MIT license, and trademark
notice. Use GitHub Copilot's mark, not Microsoft's Copilot mark. DeepSeek's mark
identifies the provider, not an independently verified Harness logo. Do not
imply endorsement. Logo images have empty alt text because the adjacent heading
names the tool; keep explicit dimensions and a contrasting background (the Kimi
color mark needs a dark background). No runtime third-party logo requests.
Keep notices under assets so they ship in the Pages artifact.

Image input requires App 0.0.39+ and the matching updated Bridge, plus an
image-capable agent/model. The site describes one PNG/JPEG and the 1 MiB phone
compression target, not universal vision support or unlimited image retention;
link to the user guide for cache and compatibility details.

## Release links

Both homepages provide a direct ARM64 APK link and three official extension
downloads. The quick-start pages recommend ARM64 and offer `arm64-v8a`,
`armeabi-v7a`, `x86`, `x86_64`, and `universal` in a native disclosure, including
without JavaScript.

The Chinese homepage and quick start download from the public CNB mirror at
`https://cnb.cool/hi-mochi/hi-mochi`, without a token, login, or GitHub API request.
It pins the verified bundled release rather than following GitHub's latest tag:
GitHub publication can precede successful CNB mirroring. Never manufacture CNB
links from unverified GitHub metadata. Source, documentation, issue links and
the independent AgentLink downloads retain their original GitHub destinations.

The English homepage and quick start keep GitHub downloads and a checked-in fallback. On page
load, `scripts.js` reads GitHub's public `releases/latest` API, validates the
returned repository URLs, and updates the displayed version, all architecture
downloads, release page links, and all three extension APK links. All assets and repository
URLs are validated even when some downloads are not displayed on that page.
Validation completes before any link changes, so missing/untrusted extensions
preserve the matching bundled release as a whole. If the API is unavailable or
rate-limited, the fallback remains usable. AgentLink pages do not request the
Mochi release API.

The bundled release is **v1.0.24**. After publishing a new release and completing
full anonymous download and SHA-256 verification of all nine CNB mirror files,
update both homepages and quick-start guides' architecture/extension URLs,
release-page URLs, and version labels together. Retain the last verified Chinese version if mirroring fails;
do not switch to GitHub or advertise a CNB APK that has not been mirrored.
The static check enforces consistency within and between languages; it does
not query GitHub or infer an unpublished version. An API outage must not send
visitors back to an obsolete installation guide.

WebDAV remains an experimental capability even though its APK is published.
Its download participates in the same all-or-nothing release update as the
base app, Mi Home and Termux. A missing or untrusted WebDAV asset retains the
entire known-good fallback set instead of mixing versions.

## Validation

Run the website-owned static checks from the repository root:

```powershell
python website\tests\validate_site.py
```

The deployment workflow runs the same check before constructing the public
artifact. Website documentation, tests, and harness files are deliberately
excluded from that artifact.

For layout or interaction changes, run the browser smoke check with optional
Playwright test tooling (not a website runtime or build dependency):

```powershell
python -m pip install --only-binary=:all: playwright
python -m playwright install chromium
python website\tests\browser_smoke.py
```

Alternatively, use `--channel msedge` or `--channel chrome` with an installed
browser instead of downloading Chromium. The check serves only `website/` at
the project path, checks all fourteen content pages at 320/390/768/1024/1280/1440 pixels, exercises
mobile/cross-page navigation and expanded setup disclosures, and mocks GitHub
responses to verify matching architecture/extension updates, API-outage fallback,
missing extension assets, and rejection of untrusted download URLs.
It also verifies the complete fallback on missing/untrusted WebDAV assets,
missing/untrusted non-ARM64 assets, all five direct links without JavaScript,
Chinese CNB links staying unchanged with no GitHub API request,
the downloadable preview's setup entry, and that setup disclosures
work with keyboard input and without JavaScript.
AgentLink checks also cover keyboard-operated setup disclosures, no-JavaScript
setup, all five loaded local logos, reading order, image-input visibility,
language switching, and desktop navigation bounds.
Use `--screenshots <directory>` to save AgentLink hero, agent, and feature-section images
at each tested width for visual review; keep generated images out of the site.

## Deployment

`.github/workflows/pages.yml` publishes `website/` when website files change
on `main`, or through a manual workflow dispatch. Set the repository's Pages
source to **GitHub Actions** before the first deployment.
The public artifact includes `wiki/`, `zh-CN/wiki/`, `agentlink/` and
`zh-CN/agentlink/` alongside the homepages. Keep every public route in the
sitemap and verify canonical and language-switch links when moving pages.
Each deployment attempt uses a distinct artifact name and waits for its metadata
to become visible before deploying, so retries cannot select an older artifact.
Use the Node 24-compatible Pages upload action; include hidden files
from the curated `_site` directory so `.nojekyll` ships with the public artifact.
Deploy the exact ID returned by the upload through the Pages REST API, retaining
the `github-pages` environment and OIDC token. Do not rediscover the artifact
through the separate Actions results service: it can return an empty list even
when REST metadata is ready. Poll for deployment success with a ten-minute bound
and cancel unfinished deployments on failure or cancellation.
