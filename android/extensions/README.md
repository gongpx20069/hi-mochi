# Extension experience guidelines

**Status: proposed redesign; not implemented by this documentation change.**
These guidelines cover every Mochi optional integration: Termux, Mi Home,
AgentLink, and future extensions. AgentLink is included in the user experience
even though it uses an independent companion-app protocol, not `extension-api`.

## Ownership and scope

- [Interaction design](../../docs/INTERACTION_DESIGN.md#7-planned-unified-extension-experience)
  owns the proposed setup flows, consent, state transitions, and recovery.
- [Extension architecture](../../docs/architecture/EXTENSIONS.md) owns current
  package trust, permissions, Binder/Messenger contracts, and task lifetime.
- [App architecture](../../docs/architecture/APP_ARCHITECTURE.md) owns module
  boundaries. UI sharing must not introduce UI or provider code into
  `extension-api`, or dependencies from Mochi into extension implementations.
- [Development](../../docs/DEVELOPMENT.md) owns build and device verification.

This README owns the redesign's measurable visual and usability acceptance
criteria. They are targets, not claims about the currently installed APKs.
Root READMEs remain user onboarding, not extension implementation manuals.

| Surface | Code owner | Required treatment |
| --- | --- | --- |
| All integration cards and completion/enablement in Mochi | This repository | One card structure and common status/action language |
| Termux and Mi Home setup Activities | This repository | Same Mochi theme and setup shell; provider-specific content |
| AgentLink authorization/access-management handoff | Mochi adapter here; native page in [android-agent-link](https://github.com/gongpx20069/android-agent-link) | Same experience specification, coordinated implementation in the companion repository |
| Android permission/install/settings screens, Termux terminal, Mi Home scanner, desktop Bridge | Platform or external application | Explain the destination and return path; do not imitate or silently operate another app's UI |

An integration is not visually complete merely because its Mochi card looks
correct. Audit the owned setup page and external handoff separately. Until the
companion changes ship, explicitly record AgentLink's external-page gap.

## Shared visual language

Use Mochi's existing Material 3 dark palette, not a separate terminal theme or
vendor palette. Provider identity belongs in the icon and name, not an
independent page background, button system, or typography.

The current palette source is
[`MochiTheme.kt`](../app/src/main/java/com/example/mochi_pet/ui/theme/MochiTheme.kt).
During implementation, extract common theme tokens and reusable UI into a
compile-time shared Android UI library consumed by the host and both extension
APKs. Prefer the same Compose Material 3 components as the host rather than
maintaining independent platform-View skins. Keep authorization and connection
logic out of that library. AgentLink needs a coordinated equivalent in its own
repository; it cannot import this repository's host application module.

| Token | Baseline | Usage |
| --- | --- | --- |
| `background` | `#0E0D12` | Page background |
| `surface` | `#1A1820` | Cards and primary content |
| `surfaceVariant` | `#27242E` | Inset code/help/status containers |
| `primary` / `onPrimary` | `#FFB7A5` / `#3B1810` | Primary action |
| `secondary` | `#D6C2FF` | Secondary emphasis, not a second primary button |
| `onSurface` / `onSurfaceVariant` | `#F7F1F2` / `#D4CDD6` | Primary and secondary text |
| `outline` | `#57515F` | Decorative separation; not the only control/state indicator |
| `error` | `#FF8A8A` | Error text/icon with explicit wording |

These values document the baseline, not permission to copy literals into each
Activity. The shared theme is the implementation source. Do not independently
force light mode or enable dynamic colors in one extension.

## Layout and accessibility targets

| Dimension | Acceptance criterion |
| --- | --- |
| Page gutters | 20 dp on compact screens; 24 dp at widths of at least 600 dp |
| Content width | At most 560 dp, centered on wider windows |
| Spacing | 4 dp grid; 8 dp related items, 16 dp card padding, 24 dp section separation |
| Corners | 20 dp content cards, 12 dp inset/code panels; primary buttons use the same shared shape |
| Typography | Title 24/32 sp; section 20/28 sp; item title 16/24 sp; body 14/20 sp; secondary metadata at least 12/16 sp |
| Command text | Monospace 14/20 sp; selectable; complete command available without truncating the copied value |
| Main action | One filled primary action per setup screen; minimum height 52 dp, allowed to grow with text |
| Touch targets | At least 48 x 48 dp, including Back, help, overflow, checkbox, and refresh |
| Footer | Safe-inset-aware, stable primary-action area outside the scrolling content; never covers the last item |
| Contrast | At least 4.5:1 for ordinary text, 3:1 for large text and essential control/state graphics |
| Status | Icon plus localized text; never color alone, and never a disabled switch as the only explanation |
| Motion | Use the host's 200-350 ms transitions; respect disabled/reduced system animation |
| Large text | 200% font scale without clipped instructions, overlapping controls, or unreachable actions |
| Small/landscape windows | Usable at 320 dp width and in landscape; vertical scrolling allowed, horizontal page scrolling forbidden |
| QR presentation | Black modules on white, at least four modules of quiet zone; never tint/invert the QR; fit available width |

Titles and instructions must wrap rather than disappear behind ellipses.
Routine form copy is left-aligned. Permission explanations and error text are
not reduced to small captions to make a screen fit. TalkBack reads the title,
current step, status, explanation, and primary action in that order. It must
not announce every QR countdown tick.

## Shared screen and card anatomy

Setup pages use: Back/title, compact numbered progress, one current-step card,
optional collapsed help, and the primary-action footer. Completed steps remain
summarized, not repeated as active buttons. Future actions stay hidden until
their prerequisites are satisfied.

Mochi cards use the same order: icon/name, connection state, one-line capability,
current next action, then expandable controls. Keep provider enablement,
individual Tools, and Skill readiness visually distinct from connection.
Version, diagnostics, reconnect/revoke, and advanced permissions belong under
management, not a permanent row of equally prominent buttons. Revocation must
remain discoverable and reachable in at most two in-app actions from the card.
Do not hide a failed or revoked permission in an overflow menu.

The Termux **Background Shell authorization** control stays a separate,
default-off advanced permission with its own native confirmation. It must
never be bundled into connection, a recommended preset, or **Enable tools and
Skill**. The same design system must not make Mi Home or AgentLink background
capabilities appear available.

## Measurable onboarding targets

| Scenario | Target and measurement |
| --- | --- |
| Every setup state | Exactly one obvious next action; the user need not infer the order of a button list |
| Already configured Termux | At most two primary Mochi/extension actions: connect/check, then explicit enablement; no terminal visit or repeated permission prompt |
| First setup, Termux already initialized | At most one required terminal visit/return and one pasted bootstrap command; measure Android permission responses separately |
| Missing Termux or uninitialized terminal | Show the prerequisite explicitly; installation/network/bootstrap time is reported separately, not hidden in a claimed setup-time target |
| Return from authorized external step | Automatically resume the pending step/check once; no separate mandatory Refresh or Connect-and-test click |
| Button feedback | Show working/disabled state within 100 ms of the native action handler; no unresponsive duplicate-submit window |
| Termux connection probe | One active attempt, bounded by the existing 15-second callback deadline; on expiry show an unknown/failed check and an explicit recovery action, not a success |
| Provider/network errors | State what is known, what is unknown, and one next action; diagnostics contain a safe stage/code, not raw provider payloads |
| Back/rotation/re-entry | Retain non-secret progress and draft selection; revalidate external facts and reject stale callbacks; never replay a write or bootstrap command |
| Optional test after setup | No LLM, billable task, device mutation, workspace creation, or automatic shell task solely to show a success illustration |
| Consent | Cancelling system/native authorization never enables a provider, Skill, or background Shell permission |

Do not publish a seconds-to-complete promise without measured device results.
For usability acceptance, have five first-time testers attempt the documented
Termux-already-installed scenario without developer coaching; target at least
four successful completions. Record total time, in-app actions, external
switches, failed steps, device/OS, and prior Termux state. This is a release
acceptance target, not existing research.

## Delivery checklist

- Cover Termux, Mi Home, and AgentLink, not only the new Termux wizard.
- Exercise Chinese and English, 320/360/600 dp widths, landscape, 100%/200%
  fonts, TalkBack, and back/rotation/re-entry.
- Capture populated, loading, empty, denied, expired, offline, and success
  states with synthetic fixture data; never real QR credentials or private
  chat/device details in committed screenshots.
- Assert step derivation, next-action mapping, cancellation, stale callbacks,
  and error recovery in deterministic tests. Include primary-action reachability
  and text overflow checks in UI coverage.
- Use real-device acceptance for system permissions, external app returns,
  callbacks, OEM background behavior, and QR scanning. Record blockers as such.
- Preserve package identities, signing channels, in-place upgrades, stored
  credentials/device choices, and existing task-lifetime guarantees.
- Land the shared shell and all three Mochi cards first, then Termux, Mi Home,
  and the coordinated AgentLink native pages. Do not call the all-extension
  redesign complete while an owned or companion integration screen is untracked.
