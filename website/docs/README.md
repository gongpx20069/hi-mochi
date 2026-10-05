# Website Development

This documentation applies only to the official Mochi website. Product and
Android application documentation remains in the repository-level `docs/`
directory.

## Structure

```text
website/
├── index.html       English homepage
├── zh-CN/           Simplified Chinese homepage
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

Keep internal assets relative so both languages work under the `/hi-mochi/`
GitHub Pages project path. The fixed `/hi-mochi/` paths in `404.html` are
intentional because GitHub Pages serves that document for arbitrary missing
URLs.

English and Simplified Chinese homepages keep the same product feature
structure, including named AI and speech accounts, document collaboration
with Notion/Tencent Docs/Feishu, and the task center. Keep onboarding and
connection-switching semantics aligned with the shipped app. Ordinary
foreground chat is not a task-center item. Feishu requires guided self-built-app
setup and authorization; do not present it as one-click login or claim support
for whole-document deletion, native spreadsheets, Bitable, or PPT editing.
Sharing copy must warn that a full link grants use of the included credentials.
The smart-home feature describes the optional signed Mi Home
extension, its supported device categories, and foreground-only latest camera
event images. It must not imply support for camera live view, playback, PTZ,
two-way audio, locks, alarms, body-composition measurements, or sharing Xiaomi
session credentials.

## Capabilities and AgentLink

The homepage groups optional capabilities under `#extensions`: Mi Home and
Termux are same-signer extension APKs; AgentLink is an independent Android app
with a computer-side Bridge. Keep all three discoverable through navigation,
the capability map, feature cards, and installation links. Show benefits first,
then setup and permissions; basic Mochi use does not require any of them.
Mi Home setup and Termux setup use native HTML disclosures, not JavaScript.
Termux copy must disclose automatic command execution after enablement, the
absence of a sandbox, and model-provider access to output used by the Agent.

AgentLink's official website lives at `agentlink/` and `zh-CN/agentlink/`.
Treat these as a standalone product destination, not a Mochi extension detail:
lead with phone/terminal continuity, Android download and computer setup.
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

Download controls include a checked-in current-release fallback. On page load,
`scripts.js` reads GitHub's public `releases/latest` API, validates the returned
repository URLs, and updates the displayed version, ARM64 download, and release
page links, plus both extension APK links. All expected assets and repository
URLs are validated before any link changes, so missing/untrusted extensions
preserve the matching bundled release as a whole. If the API is unavailable or
rate-limited, the fallback remains usable. AgentLink pages do not request the
Mochi release API.

The bundled fallback is **v1.0.13**. After publishing a new release, update
both homepages' base/extension download URLs, release-page URLs, and version labels together.
The static check enforces consistency within and between languages; it does
not query GitHub or infer an unpublished version. An API outage must not send
visitors back to an obsolete installation guide.

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
the project path, checks all four pages at 320/390/768/1024/1280/1440 pixels, exercises
mobile/cross-page navigation and expanded setup disclosures, and mocks GitHub
responses to verify matching base/extension updates, API-outage fallback,
missing extension assets, and rejection of untrusted download URLs.
AgentLink checks also cover keyboard-operated setup disclosures, no-JavaScript
setup, all five loaded local logos, reading order, image-input visibility,
language switching, and desktop navigation bounds.
Use `--screenshots <directory>` to save AgentLink hero, agent, and feature-section images
at each tested width for visual review; keep generated images out of the site.

## Deployment

`.github/workflows/pages.yml` publishes `website/` when website files change
on `main`, or through a manual workflow dispatch. Set the repository's Pages
source to **GitHub Actions** before the first deployment.
The public artifact includes `agentlink/` and `zh-CN/agentlink/` alongside the
homepages. Keep both routes in the sitemap and verify their canonical and
language-switch links when moving pages.
