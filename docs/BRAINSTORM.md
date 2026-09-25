# Mochi product ideas

These are deferred ideas, not shipped features or a commitment to implement
them next. Current requirements belong in `PRD.md`; delivery status belongs
in `ROADMAP.md`.

The current iteration implements the task center and configuration checks.
The following ideas retain the other recommendations from that discussion.

## Voice and background reliability

Make everyday use dependable before adding more integrations.

- Exercise wake, recognition, synthesis and interruption after long idle
  periods, screen locking, network changes and Android process recreation.
- Explain whether a scheduled run is queued, missed, interrupted or finished.
  Define recovery policies explicitly; never blindly replay a task that may
  already have changed a file or a remote system.
- Measure wake-to-listening latency, recognition-to-first-response latency,
  overnight battery use and missed-run rates on representative devices.
- Keep Android permission and battery-setting guidance actionable without
  promising exemption from OEM background restrictions.

## Ready-to-use scenario templates

Build on existing Scheduled Agents and Skills rather than another executor.

- Morning briefing: weather, today's agenda and important unfinished todos.
- Evening review: completed work, remaining items and tomorrow's plan.
- Weekly research: gather sources, summarize findings and write to an
  explicitly selected Notion or Tencent Docs destination.
- Ask for only the time, topics and destination needed by the template.
  Show dependencies and the resulting editable instruction before enabling it.
- Distinguish informational output from actions that modify external data.
  Installing a template must not enable Providers, Tools or permissions.

## Memory management and local backup

Keep conversation history separate from durable, user-reviewable preferences.

- Let users inspect, correct and delete saved memories with clear provenance.
- Resolve outdated or contradictory preferences rather than accumulating
  competing instructions indefinitely.
- Offer versioned local export/import for persona, conversation and planner
  data with preview and explicit merge/replace behavior.
- Exclude API keys, extension sessions, camera images and command output by
  default. Provider sharing remains a separate, explicit flow.
- Test restoration to a fresh installation and recovery from interrupted
  imports. Define schema migration and secret reconfiguration requirements.

## Recurring calendar events and local reminders

Complete the planner's recurrence expansion and notification behavior.

- Define timezone, daylight-saving, all-day and recurrence-exception behavior.
- Support editing one occurrence versus the whole series without duplicates.
- Distinguish planner reminders from Agent schedules: a reminder should not
  need a model call, network access or a running Termux process.
- Test notification denial, exact-alarm restrictions, reboot reconciliation
  and duplicate suppression.

## Prioritization

Favor repeatable daily usefulness and reliability over the number of optional
extensions. Validate a small set of complete user journeys before expanding
platform coverage. Promote an idea into the PRD, interaction contract and
roadmap only after its scope and measurable acceptance conditions are agreed.
