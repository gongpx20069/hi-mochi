# Documentation

The default English [`README.md`](../README.md) and localized
[`README.zh-CN.md`](../README.zh-CN.md) are for Mochi users. This directory
contains the product and engineering source of truth.

## Product

| Document | Purpose |
| --- | --- |
| [`PRD.md`](PRD.md) | Product scope, principles, and acceptance criteria |
| [`INTERACTION_DESIGN.md`](INTERACTION_DESIGN.md) | Screens, gestures, voice navigation, and accessibility |

## Architecture

| Document | Purpose |
| --- | --- |
| [`architecture/TECHNICAL.md`](architecture/TECHNICAL.md) | Runtime, providers, memory, Skills, and platform design |
| [`architecture/APP_ARCHITECTURE.md`](architecture/APP_ARCHITECTURE.md) | Modules, state ownership, persistence, and dependencies |
| [`architecture/AGENT_TOOLS.md`](architecture/AGENT_TOOLS.md) | Tool schemas, MCP, safety, and UI directives |
| [`architecture/AGENT_BROWSER.md`](architecture/AGENT_BROWSER.md) | Visible per-turn browser Tools, lifecycle, and safety |
| [`architecture/CARD_PRESENTATION.md`](architecture/CARD_PRESENTATION.md) | Trusted Agent card evidence and rendering |
| [`architecture/VOICE_RUNTIME.md`](architecture/VOICE_RUNTIME.md) | Wake word, recognition, audio focus, and speech runtime |

## Delivery

| Document | Purpose |
| --- | --- |
| [`ROADMAP.md`](ROADMAP.md) | Current implementation status and remaining work |
| [`DEVELOPMENT.md`](DEVELOPMENT.md) | Repository layout, local build, tests, and device installation |
| [`../CONTRIBUTING.md`](../CONTRIBUTING.md) | Issue, Provider request, and pull-request guidance |

## Authority

Product requirements override interaction details. Product documents override
architecture documents, and architecture documents override roadmap status.
Code and generated schemas must implement the contracts; they do not silently
redefine them.

User-facing capability and third-party API setup links are maintained in the
root [`README.md`](../README.md), including the complete built-in Skills table.

When two documents conflict, fix the lower-authority document instead of
adding an exception.

## Maintenance rules

- Keep one authoritative definition and link to it elsewhere.
- Separate current behavior from planned behavior.
- Update the relevant contract in the same change as behavior.
- Keep setup and verification commands in `DEVELOPMENT.md`.
- Delete obsolete documents and code; do not retain in-tree archives.
- Every path, command, and status claim must be verifiable from the repository.
