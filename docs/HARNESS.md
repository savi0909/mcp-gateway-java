# Engineering harness

This is the Phase 1 engineering runner, separate from the MCP gateway product
control plane. It reuses the existing Java build, requirements and acceptance
trackers. See [ADR 0010](decisions/0010-engineering-harness.md), the
[approved plan](handover/refined-autonomous-development-plan.md) and
[Phase 1 handover](HANDOVER_PHASE1.md).

The owner authorized `PILOT-01` after Phase 1; see the committed
[authorization](handover/pilot01-authorization.md). The first real attempt on
2026-10-18 stopped with `Malformed Codex JSONL` before candidate validation,
Maven verification or independent review. Its state is `needs_review`, with one
attempt and a clean retained worktree; the other 11 tasks remain pending. See
[the Phase 2 handover](HANDOVER_PHASE2.md) for retained evidence, read-only
inspection commands and the subsequent local adapter repair. The repair still
requires independent review before renewed pilot approval. Batch is disabled. No gateway business
behavior or dependency version changed, and the real pilot is not complete.

## Files and responsibilities

| File | Responsibility |
| --- | --- |
| [backlog](../tasks/backlog.json) / [schema](../tasks/schema.json) | Single machine task-state authority and strict field types/limits |
| [configuration](../harness/config.toml) | Fixed command policy, process/runtime/repair limits, real model IDs and suite baseline |
| [orchestrator](../harness/orchestrator.py) | Selection, phase gate, isolated worktree, lifecycle, Git checkpoints and recovery |
| [common primitives](../harness/common.py) | Strict JSON/schema subset, path checks, approved-scope hashes, atomic writes, Git and OS lock |
| [process adapter](../harness/processes.py) | Codex preflight/JSONL, bounded output, minimal environment and process-tree termination |
| [validator](../harness/validator.py) | Exact changed-path/content checks, fresh Maven reports and independent review verdict |
| [PowerShell verifier](../scripts/verify.ps1) / [POSIX verifier](../scripts/verify.sh) | Wrapper verify in a fresh per-run build directory; exit-code propagation |
| [tests](../harness/tests/test_harness.py) | Isolated repositories, fake child processes, local bare remotes, failure/crash cases |
| [stream regression tests](../harness/tests/test_process_streams.py) | Separate stdout/stderr, shared byte limits, strict JSONL rejection and secret-safe persisted failures |

Use Python 3.13, Java 21, the repository's Maven Wrapper and Git. No Python package
installation is needed. Live execution also needs a supported authenticated Codex
CLI and its sandbox. Local inspection found CLI 0.162.1 and the configured
`gpt-6-astra`/high default. The harness explicitly selects model roles: routine
implementation `gpt-6.1-sol`/medium; complex implementation and review
`gpt-6-astra`/high. Cached availability is checked before a run; actual service
availability remains a runtime condition. No silent model fallback occurs.

## Commands available now

Run from the controlling repository root. The harness must be committed before a
real task can execute, and its checkout must be clean. Bootstrap/status/dry-run do
not invoke a model. Bootstrap validates shipped control files without overwriting
them; it deliberately does not invent or authorize missing task definitions.

```powershell
python -B -m harness.orchestrator bootstrap
python -B -m harness.orchestrator status
python -B -m harness.orchestrator dry-run
python -B -m harness.orchestrator preflight
python -B -m unittest discover -s harness/tests -v
pwsh -NoProfile -File scripts/verify.ps1
```

The verifier prints the Maven results and puts output under
`target/harness/verify-<unique-id>/`. To retain a chosen run name:

```powershell
pwsh -NoProfile -File scripts/verify.ps1 -RunId verify-my-run
```

An existing output directory is rejected, never deleted or reused. The corresponding
POSIX command is `sh scripts/verify.sh . verify-my-run`. Both wrappers invoke
`mvnw -B -ntp -Dgateway.build-directory=target/harness/<run-id> verify`; they do not
use `clean`, skip tests or restart an existing demo.

`preflight` inspects installed CLI help/version and model-cache metadata without
printing private configuration or sending a model request. Project Codex/hook
configuration causes a review stop. `--ignore-user-config` prevents the runner
from inheriting unrelated user configuration. This is not evidence that a live
model request or the Windows sandbox has succeeded.

## Real task approval and execution (later gate)

Phase 1 does not authorize these execution commands. After explicit pilot approval,
record the owner-approved scope in a repository document, then review and commit
the backlog changes. The proposed `PILOT-01` corresponds to P2-01 in the Phase 0
plan: prove ordinary membership activation using the existing Boot/SDK fixtures.

An authorized maintainer must set:

- `pilot_authorization` to the committed approval-document path;
- the selected task's `status` to `ready`, `requires_human_approval` to `false`,
  `base_commit` to the reviewed source revision and `phase` to 2;
- `approval` to `{reference, scope_sha256, approved_by: "owner"}` with the exact
  approved task fields hashed by `harness.common.scope_hash`;
- explicit exact allowed file paths, criteria, requirements and dependencies.

There is no auto-approve command. The record is an owner-reviewed repository trust
input, not a cryptographic identity system. An agent cannot legitimately create its
own approval. The schema/hash prevents accidental or candidate-side scope drift;
it cannot defend against a hostile administrator controlling the entire host.

The source revision precedes the approval commit: a commit cannot include its own
SHA. Only backlog/approval-document changes may follow the approved source revision.
Any intervening source or runner-policy change requires a reviewed new base and
scope hash. Completed dependencies require durable verification/review evidence,
their content commit and inclusion in the selected task's approved source history.

After the approval record is reviewed and committed:

```powershell
python -B -m harness.orchestrator dry-run --task PILOT-01
python -B -m harness.orchestrator single-task --task PILOT-01
```

The dry-run displays the selected task, approved base, paths, specifications, model,
command stages, limits and intended Git effects. It creates no claim/worktree.
An unapproved, non-ready or dependency-blocked task cannot execute. Phase 3 backlog
entries remain planning inputs and are rejected by the current execution policy.

## Single-task lifecycle

1. Validate schema, references, scope/approval and dependencies; require a clean
   controlling checkout. Acquire the shared Git-directory OS lock.
2. Record durable intent and task ownership. Create branch
   `harness/<task-id>-<run-id>` and sibling worktree
   `.<repository-name>-harness-worktrees/<run-id>` outside the controlling checkout.
3. Start a fresh Codex process in that worktree with the task-specific prompt,
   explicit model/effort, `workspace-write`, no inherited user configuration,
   structured output and bounded runtime. Additional permission is denied, not bypassed.
4. Reject history/index changes, deleted files, symlinks/junctions/hardlinks,
   out-of-scope files and an empty candidate. Bind evidence to actual content hashes.
5. Invoke the trusted verifier outside the agent. Require fresh XML, successful
   packaging, all baseline suites and no failures/errors/skips. Extra tests are
   allowed; unexplained loss of baseline tests is not.
6. Launch a separate read-only review process over the same candidate. Any unresolved
   finding blocks this first pilot. Verify content again after both stages.
7. Source/test or review failures may receive up to three focused repairs after the
   initial attempt. Persist the attempt count before each process. Infrastructure,
   model, permission and scope failures stop immediately.
8. Commit exact task files on their branch; create a separate controlling-branch
   backlog metadata commit containing the content SHA. Push explicit refs to origin
   under standing authorization. Never force-push, auto-merge or deploy.

The agent itself cannot stage/commit/push, change approvals/verification, delete,
spawn more agents or call live services. A source task needing protected build,
CI, harness or requirements changes is outside this pilot runner; handle it as
separately approved, directly reviewed work before trusting the updated runner.

Configuration caps: one task, 60-minute run, 20-minute implementation/repair,
15-minute verification, 10-minute review and at most three repairs. Each child
process uses the remaining run budget. Git operations have separate 60-second
timeouts. Completion reconciliation/publishing can finish after an expired agent
budget without re-executing the agent. No hard token/cost enforcement is claimed.

## Stop, inspect and resume

Press Ctrl+C in the owning runner console to request cancellation. Its process
supervisor terminates the owned child tree. A fresh task does not start after a
stop. Inspect persisted evidence with:

```powershell
python -B -m harness.orchestrator status
python -B -m harness.orchestrator inspect --run-id <32-character-run-id>
python -B -m harness.orchestrator resume --run-id <32-character-run-id>
```

State/evidence lives in the shared Git directory under
`engineering-harness/runs/<run-id>/state.json`; `git rev-parse --git-common-dir`
locates it. It contains safe process metadata, verification counts, candidate
hashes, review finding codes, attempts, deadline and commit references. Raw agent
stdout and arbitrary reviewer prose are not persisted. Use file paths and finding
codes to inspect affected code; the reviewer does not return secret-bearing prose.

Restart does not reset the attempt or time budget. If a process died while an
implementation was running, the run becomes `needs_review` and will not replay it.
Inspect the retained diff and evidence, then explicitly approve a newly scoped task
if further implementation is needed. Do not clear counters or reset the worktree.

Safe interruption stages resume validation/review or finalize recorded content:
unique commit markers, exact parents/paths and hashes distinguish an existing
content/metadata commit from an uncommitted candidate. A failed push leaves a
completed task with publication pending; resume publishes without reimplementation.
External edits to primary task state or candidate content are preserved and stop
automatic reconciliation. One OS lock covers all worktrees; stale PIDs are never
used to steal a live lock, and the lock file is retained after release.

Do not automatically delete fixtures, logs, pending state files, lock files,
branches or worktrees. Deletion requires explicit owner permission. Tests retain
their fixtures under ignored `target/harness-tests/` (and adjacent retained
worktree directories within that fixture area).

## Validation scope and limitations

The process supervisor drains stdout and stderr concurrently. Its `output` field
contains stdout only; stderr is counted and discarded. Both streams share the
existing output byte cap (2,000,000 by default; 8,000,000 for Maven). Codex success
and failure evidence retains only exit code, duration, stream byte counts and,
on success, event count. Malformed stdout, explicit protocol failures, missing
completion/messages and invalid structured reports still stop without retries.
The new stream suite reproduces the former merged-stream failure with local
children; it does not establish the exact cause of the discarded pilot output.

The harness suite uses actual isolated Git repositories, local remotes and child
processes with fake implementation/review/verifier behavior. It exercises task
contracts, approval/dependency/dirty-state gates, repair exhaustion, mutation
checks, crash stages, publication failure, process-tree cancellation and locks.
Codex JSONL/argv behavior uses a fake process; no paid model execution is part of
the test suite. Windows and Git-for-Windows shell-wrapper tests are local evidence;
Linux CI results and real sandbox/provider execution remain unverified here.

The Java baseline was also rerun through the actual PowerShell wrapper. Retaining
the existing fixture suite is necessary but not sufficient to prove new feature
correctness; review must examine the new assertions and requirements. Required-suite
minimums are a regression guard, not a claim that coverage can never be weakened.

Sandboxing, worktree separation and scope checks protect the intended trusted
repository workflow. Building arbitrary malicious code is not a supported security
boundary: tests execute project code. Stronger host/network isolation is required
before allowing untrusted contributors or third-party code. Audit power-loss,
distributed state and hostile-host recovery are not supplied by this local runner.

The existing Windows/Linux CI workflow now includes Python harness tests; a local
pass does not imply the hosted CI run has completed. See the handover for the
actual local test counts and any remaining limitations.
