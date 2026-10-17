# ADR 0010: Bounded single-task engineering harness

Date: 2026-10-17. Status: accepted for the owner-requested Phase 1 bootstrap.
The owner approved the refined Phase 0 plan by requesting "start phase 1" and
subsequently requested completion followed by a handover. This does not approve
the real pilot, batch, new gateway capabilities or deployment.

## Decision

Use Python 3.13 standard-library tools alongside the unchanged Java/Maven gateway.
Keep the existing requirements, WORKLOG, acceptance domains and ADR directory.
`tasks/backlog.json` owns machine task status and references requirements rather
than restating them. `tasks/schema.json` defines its strict, deliberately small
supported JSON Schema subset. `harness/config.toml` owns trusted limits, model
roles and required Maven-suite minimums. Unsupported schema/configuration fails.

The controlling checkout runs the committed harness. An approved task has exact
file paths, requirements, criteria, an approved source revision and an owner
approval record whose hash covers those fields. Approval metadata commits may
follow that source revision; changes to source after approval require reapproval.
One OS-held repository lock serializes all worktrees and machine-state writes.
The agent operates in a separate sibling worktree outside the controlling checkout
and outside `.git`. It cannot legitimately alter runner policy or status.

Fresh Codex implementation and separate read-only review use observed CLI syntax,
explicit sandbox/model settings and no inherited user configuration. Noninteractive
`-a never` fails operations needing additional permission; it does not enable full
access or bypass the sandbox. Keep local Warp integrations disabled. Do not use
resume/fork for implementation attempts, or delegate additional agents.

The external runner checks scope and candidate content hashes, invokes trusted
verification entry points, reads fresh Maven XML and requires review on that same
candidate. Missing suites, failed/skipped tests, history/index changes, deletions,
out-of-scope edits or unresolved review findings prevent completion. The first
pilot blocks on any unresolved finding, not only high/critical findings.

Allow one initial attempt plus at most three focused repairs for actual source/test
or review failures. Infrastructure, permission, model and unsafe-state failures stop
without source repair. Persist attempts and an absolute deadline before executing;
restart does not renew either budget. An interrupted implementation requires review
instead of automatic replay. Safe verification/commit reconciliation may resume.

Windows processes start suspended, enter a kill-on-close Job Object and then resume.
This contains descendants before any child code executes. POSIX uses a process group.
Timeout, cancellation and even parent success terminate leftover descendants.
Keep output bounded in memory; persist only whitelisted metadata and finding codes.
No raw model stdout, environment dump, bearer token or private response is logged.

## Durable completion and publication

Write run intent before updating the tracked backlog. State lives under the shared
Git directory's `engineering-harness/runs/<id>/state.json`; each write is fsynced
and atomically replaced. Preserve pending write files after interruption rather
than deleting them. This proves tested process-crash behavior, not power-loss or
hostile-host durability.

Commit validated task files on their branch, then record the content SHA in a
separate backlog metadata commit on the controlling branch. Unique run markers,
parent commits, exact changed paths and candidate hashes reconcile crashes between
those operations. No commit tries to include its own SHA. Publication pushes
explicit refs without force or merge; push failure retains completion and permits
publication-only resume. A dependency's code must be integrated into a later
task's approved base before that dependency can be used.

Git subprocesses disable hooks for that command so an unrelated host hook cannot
execute inside this controlled pipeline. No global Git/plugin setting is changed.
All checks are explicit. Tests use fixture Git repositories and local bare remotes.
Task/worktree/log cleanup is never automatic and requires explicit deletion consent.

## Alternatives and consequences

- A new Java service or distributed scheduler would add complexity unrelated to
  the selected single-host engineering problem. Keep Java as the gateway language.
- YAML would require another Python parser; JSON plus TOML use the installed stdlib.
- Separate progress/blocker files would duplicate status; derive them from the backlog
  and run evidence, with narrative handoff in the existing WORKLOG.
- Trusting agent-reported test success would miss stale reports, scope expansion and
  review failures. The independent verifier is authoritative.
- Worktree isolation and post-change checks are not a hostile-code security sandbox.
  The approved pilot is restricted to trusted repository contributors. OS sandbox
  behavior for a real Codex call still needs the separately approved pilot evidence.
- Full API/version compatibility, live model access, hosted CI and Linux process
  behavior cannot be inferred from Windows mock tests. Report those limits explicitly.
- State and retained worktrees use disk over time; manual cleanup remains subject
  to the no-delete instruction. No cost/token enforcement is claimed; task, repair,
  process/runtime and output limits are enforced independently.

## Gates

Phase 1 delivers bootstrap/status/dry-run/preflight, a mocked single-task lifecycle,
recovery tests, documentation and verification. All real tasks remain pending.
Phase 2 requires owner approval of the real pilot and its task scope; batch remains
disabled in code/configuration until a separately reviewed future change. Neither
gate is satisfied by a successful Phase 1 test run. See [usage](../HARNESS.md) and
[handover](../HANDOVER_PHASE1.md) for exact evidence and next action.
