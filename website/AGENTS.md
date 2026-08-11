# Website AGENTS.md

The official website is an independent subproject. Its code, assets, tests,
tooling, and documentation are owned entirely within `website/`.

## Start here

Read [`docs/README.md`](docs/README.md) before changing the website. Keep
website behavior and development instructions there instead of adding them to
the repository-level Android product documentation.

## Boundaries

- Keep website source, assets, tests, fixtures, scripts, configuration, and
  documentation under `website/`.
- Keep the website dependency-free unless an explicit website requirement
  justifies introducing a build tool.
- Do not add website implementation details to the root README files or the
  repository-level `docs/` directory.
- Do not make the website depend on Android build output or local development
  artifacts.
- `.github/workflows/pages.yml` is the sole allowed integration outside this
  directory because GitHub requires workflows at the repository level. It
  must deploy only the `website/` artifact.
- Keep internal asset links compatible with the `/hi-mochi/` GitHub Pages
  project path and preserve independent English and Simplified Chinese routes.

## Validation

Use the smallest deterministic website checks that cover the change. Verify
local resources, anchors, responsive widths, browser JavaScript behavior, and
the Pages artifact when those surfaces are affected.
