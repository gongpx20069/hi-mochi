# AGENTS.md

This file is a harness entry point, not a duplicate specification. Read the
smallest relevant authoritative documents before changing the repository.

## Start here

| Change | Read first |
| --- | --- |
| Product behavior or scope | `docs/PRD.md` |
| Screens, navigation, or voice UX | `docs/INTERACTION_DESIGN.md` |
| Modules, state, storage, or dependencies | `docs/architecture/APP_ARCHITECTURE.md` |
| Runtime, providers, memory, or Skills | `docs/architecture/TECHNICAL.md` |
| Tools, MCP, navigation directives | `docs/architecture/AGENT_TOOLS.md` |
| Optional signed extension APKs | `docs/architecture/EXTENSIONS.md` |
| Trusted cards | `docs/architecture/CARD_PRESENTATION.md` |
| Wake word, STT, TTS, or audio | `docs/architecture/VOICE_RUNTIME.md` |
| Build, tests, installation, repository layout | `docs/DEVELOPMENT.md` |
| Current priorities and delivery status | `docs/ROADMAP.md` |
| Documentation ownership | `docs/README.md` |
| Official website code, tests, or documentation | `website/AGENTS.md` |

When documents conflict, follow the authority order in `docs/README.md` and
fix the lower-authority document in the same change.

## Harness loop

1. Inspect the relevant contract, implementation, tests, build tasks, and diff.
2. Define an observable acceptance condition.
3. Make the smallest complete change.
4. Run the narrowest deterministic check that proves it.
5. Fix failures instead of suppressing or explaining them away.
6. Review architecture, privacy, migrations, cancellation, and stale docs.
7. Report only outcomes that actually ran.

Turn repeatable mistakes into repository controls: tests, fixtures, typed
contracts, lint rules, build gates, or a concise update to an authoritative
document. Do not rely on conversation history.

## Repository rules

- `android/` is the only application runtime.
- `website/` is an independent subproject. Keep all website-specific source,
  assets, tests, tooling, and documentation inside `website/`; the only
  permitted repository-level integration is its GitHub Pages workflow under
  `.github/workflows/`.
- Root README files and `docs/` describe the Mochi product and Android
  application. Do not place website implementation or development
  documentation in those locations.
- Keep model access behind typed tools and validated response contracts.
- Preserve the dependency and state ownership rules in the architecture docs.
- Room changes require a migration, schema snapshot, and migration coverage.
- Secrets stay in Keystore-backed storage and never enter source, logs, tests,
  screenshots, prompts, or exports.
- Use the checked-in Gradle wrapper and commands in `docs/DEVELOPMENT.md`.
- Update the authoritative document when behavior or contracts change.
- Delete obsolete code and documentation rather than keeping in-tree archives.

A task is incomplete when a required gate did not run; record the exact
blocker without claiming completion.
