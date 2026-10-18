# Phase 2 pilot attempt handover

Date: 2026-10-18. **PILOT-01 is incomplete and requires review.** Its first real
attempt stopped with `Malformed Codex JSONL` before producing a candidate or
reaching independent Maven verification/review. This handover records the failure;
it does not approve harness repair, another model call, batch or gateway changes.

## Read first

1. [AGENTS.md](../AGENTS.md), [WORKLOG current state/latest entry](../WORKLOG.md)
   and [implementation plan](IMPLEMENTATION_PLAN.md).
2. [Phase 1 handover](HANDOVER_PHASE1.md), [harness usage](HARNESS.md),
   [ADR 0010](decisions/0010-engineering-harness.md) and
   [pilot authorization](handover/pilot01-authorization.md).
3. [Backlog](../tasks/backlog.json), [configuration](../harness/config.toml),
   [process adapter](../harness/processes.py),
   [orchestrator](../harness/orchestrator.py) and retained local run state.

The backlog remains the machine task-state authority. Narrative documents do not
reset status, attempts, deadlines or approval hashes.

## Recorded attempt and evidence

| Item | Verified record |
| --- | --- |
| Approval source revision | `200b7f943b57c44830b37360b95a1abd70c0ab57` |
| Approval commit / worktree HEAD | `0f516ebd7b36cab7e1910853536e4216910a9db3` |
| Published failure handoff commit | `564593f` on `main`; authorization and failure documentation were pushed to origin |
| Run ID | `43d56cc99c694b6781f51674d0165b69` |
| Task / stage / attempts | `PILOT-01` / `needs_review` / 1 |
| Stop reason | `Malformed Codex JSONL` |
| Retained branch | `harness/pilot-01-43d56cc99c694b6781f51674d0165b69` |
| Candidate / Maven / review / content commit | None recorded |
| Other tasks / batch | 11 pending / disabled |

Exact executed command:

```powershell
python -B -m harness.orchestrator single-task --task PILOT-01
```

Exit code 1; safe returned result:

```json
{"status": "stopped", "reason": "Malformed Codex JSONL"}
```

The prescribed implementation process was launched with the pinned
`gpt-6.1-sol`/medium configuration. Parsing failed, so neither successful model
completion nor working sandbox execution is established. The separate
`gpt-6-astra`/high reviewer was not reached. No retries or model fallbacks ran.

Retained local evidence:

- State under the shared Git directory:
  `engineering-harness/runs/43d56cc99c694b6781f51674d0165b69/state.json`.
- Worktree:
  `D:/ai-projects/.mcp-gateway-server-java-harness-worktrees/43d56cc99c694b6781f51674d0165b69`.
- State has one `process_failure` event and no candidate, verification, review,
  content-commit or metadata-commit record for this run.
- The worktree was checked clean at the approval commit, and the ownership lock
  was successfully reacquired/released after failure.

These state files and the worktree are local artifacts, not transferred by a Git
push. The failed task branch has no content commit and was not published. Preserve
all artifacts; do not delete, clean, reset counters or manufacture completion.
Raw child output was discarded by design and cannot be recovered from this state.

## Diagnosis boundary and next repair scope

Static inspection found that `run_process` merges stderr into stdout and
`Codex.execute` then parses each resulting line as JSON. A diagnostic outside JSON
could explain the failure. This is a hypothesis; the malformed line was discarded,
so the exact cause is unconfirmed. The existing fake-process suite did not prove
compatibility with this real CLI run.

The approved pilot allows only its named test/fixture and catalog/worklog files;
it excludes the process adapter and trusted harness policy. A subsequent explicit
repair request must define the exact repair paths and criteria before changing
that trust boundary. Suggested scope for review:

1. Reproduce the stream/parser failure with deterministic local child fixtures.
   Confirm the installed CLI's actual event/report contract from local evidence.
2. Keep JSON stdout separate from diagnostic stderr, with bounded capture and
   secret-safe metadata. Continue rejecting malformed protocol stdout, explicit
   failure events, missing completion and invalid structured reports. Do not hide
   arbitrary bad lines or persist raw model output to obtain a passing run.
3. Cover diagnostic-only stderr alongside valid JSONL, malformed stdout, failed
   processes, output bounds and secret canaries; preserve process-tree containment,
   sandbox settings, deadlines and the no-infrastructure-retry rule.
4. Run `python -B -m unittest discover -s harness/tests -v` and independently review
   the repaired trusted runner before refreshing pilot approval against a new base.
5. Reconcile the old `needs_review` record explicitly. Do not replay its execution
   or reset its attempt count. Decide and document how a newly authorized attempt
   is represented before launching it; the current runner rejects an outstanding
   needs-review task and previously attempted tasks.

This is proposed repair scope, not authorization to implement it. The documentation
request starts no repair, live model call or new pilot attempt. A later successful
pilot still requires separate review before any batch policy change.

## Safe next-session checks

Run these read-only checks from the controlling checkout, not the candidate:

```powershell
git status --short --branch
python -B -m harness.orchestrator bootstrap
python -B -m harness.orchestrator status
python -B -m harness.orchestrator inspect --run-id 43d56cc99c694b6781f51674d0165b69
git -C D:/ai-projects/.mcp-gateway-server-java-harness-worktrees/43d56cc99c694b6781f51674d0165b69 status --short
```

Do not use `single-task` or `resume` to bypass the stopped state. Cached CLI/model
preflight support does not prove a valid live JSONL exchange. Keep Warp hooks
and integrations disabled under the saved Windows operational instructions.

## Verification and product limits

The failed pilot ran no Maven verification or Python harness test suite and
delivered no membership activation test. Phase 1's 49 passing harness tests and
128 unit/startup plus 34 integration tests are historical evidence from 2026-10-17,
not fresh results for this attempt. Hosted CI/Linux behavior remains unverified.
No gateway Java, dependency, endpoint, runtime policy or live service changed;
identity/catalog acceptance was not promoted.

This documentation follow-up checks current run state, backlog consistency and
Markdown/text integrity only. See the latest WORKLOG entry for its executed checks.

## Copyable next-session prompt

```text
Read AGENTS.md, WORKLOG.md, docs/IMPLEMENTATION_PLAN.md,
docs/HANDOVER_PHASE2.md, docs/HARNESS.md and ADR 0010.
Inspect PILOT-01 run 43d56cc99c694b6781f51674d0165b69 and its retained worktree.
The task is needs_review after one Malformed Codex JSONL failure; it is incomplete.
Treat merged diagnostic stderr as an unconfirmed hypothesis. Do not replay the run,
reset counters, broaden pilot scope or claim old tests as fresh evidence.
If the owner explicitly requests harness repair, define its exact paths/criteria,
reproduce locally, preserve strict protocol validation and bounded secret-safe
process handling, run the full harness suite and review the repaired trust root.
Reconcile failed state and refresh pilot approval before any separately authorized
new real attempt. Batch remains disabled; do not merge, deploy or delete files.
```
