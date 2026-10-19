---
name: jh-release
description: >-
  Ship a WhereAmI change: version bump check, build gate, APK install on the connected phone, push,
  GitHub PR into master, CI watch, merge via PR and post-merge cleanup. Use when the user asks to
  install the APK, push, publish, open a PR, merge to master, or "ship/release" the current work.
---

# Release Flow (`jh-release`)

`AGENTS.md` sections 4-5 are authoritative: master changes only through a green PR, and pushing,
installing and merging each need explicit user instruction. This skill only scripts those steps.

## Steps (run only the ones the user authorised)

```bash
R=.agents/skills/jh-release/scripts/release.sh
$R check                          # not on master, clean tree, versionCode/versionName bumped vs origin/master, gate
$R install                        # adb install -r + print installed versionName/versionCode
$R pr "<conventional title>" /tmp/pr_body.md   # push branch, open PR, watch checks
$R merge <pr-number>              # only when checks are green; merge commit, delete branch, sync, prune
```

## Before `pr`

- Docs current: `docs/ENHANCEMENT_TRACKER.md` round entry, `docs/FEATURES_INVENTORY.md`,
  `docs/ARCHITECTURE.md` (ADR or amendment for behaviour changes), `AGENTS.md` / `GEMINI.md` if an
  invariant changed.
- PR body (write it outside the repo, e.g. `/tmp/pr_body.md`): summary, evidence (test count, replay
  metrics for tracking changes), risks, docs touched.
- No personal data in the diff: no `device_backup/diagnostics`, databases, logcats, frames or videos
  (`git diff --stat origin/master` before pushing).

## After `merge`

- Report the PR link, merge commit, installed version, and anything not verified on device.
- Delete ephemeral telemetry dirs (`device_data_round_*`, `/tmp/wai_*`) once fixes are verified.
- No Slack or other notifications: none is configured for this project.
