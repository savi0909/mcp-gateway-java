# Phase 2 pilot attempt handover

Date: 2026-10-18. **PILOT-01 is incomplete and requires review.** Its first real
attempt stopped with `Malformed Codex JSONL` before producing a candidate or
reaching independent Maven verification/review. The original handover recorded the
failure without approving repair. The owner's subsequent instruction to continue
from this document selected the bounded adapter repair described below. No new
pilot attempt, model call, batch or gateway change was included in that repair.

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

At the failed-run revision, `run_process` merged stderr into stdout and
`Codex.execute` parsed each resulting line as JSON. A diagnostic outside JSON
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

This was proposed scope at the original documentation handoff. The subsequent
continuation selected local repair, with the exact paths and results recorded
below. A later successful pilot still requires separate review before any batch
policy change.

## Subsequent bounded adapter repair (2026-10-18)

The owner resumed with `continue - .../docs/HANDOVER_PHASE2.md`. This selected
the proposed local diagnosis/repair, not replay of the failed task. Exact repair
paths: `harness/processes.py`, `harness/tests/test_process_streams.py`,
`harness/tests/test_harness.py`, `docs/HARNESS.md`, `docs/IMPLEMENTATION_PLAN.md`,
this handover and `WORKLOG.md`. The configuration, orchestrator, task state,
approval record, build/CI and gateway source remain unchanged.

A deterministic child emitting valid JSONL on stdout and a diagnostic on stderr
reproduced `Malformed Codex JSONL` before the change. The supervisor now drains
both streams concurrently, returns stdout only and counts/discards stderr under
the same combined byte cap. Failure metadata contains only safe exit/duration/
stream-count fields. Malformed stdout, explicit failure events, missing completion
or messages and malformed/schema-invalid reports remain blocking failures;
there is no retry or sandbox-policy change.

Local CLI 0.162.1 `exec --help` confirms JSONL events are written to stdout and
the required flags exist. Event/message shapes were cross-checked against
[official non-interactive documentation](https://developers.openai.com/codex/noninteractive/).
Neither help nor documentation establishes a successful live exchange with this
installed CLI. The original pilot's exact malformed line remains unrecoverable.

The full suite exposed a separate test isolation defect: synthetic repositories
copied the real pilot's attempt and run state. The first expanded run had 9
failures and 16 errors across 62 tests because the replay guard correctly rejected
those already-attempted fixtures. The fixture now defines its own fresh task;
the real backlog and replay guard were not changed. A review-fixture byte-count
assertion also initially failed due to Windows CRLF; its child now emits explicit
bytes. Both exploratory logs/fixtures and the failed pilot are retained.

Fresh command: `python -B -m unittest discover -s harness/tests -v`.
**63 tests passed in 115.936 seconds, zero failures/errors/skips**. The 14 new
stream tests exercise diagnostics, sandbox arguments for both roles, malformed/missing/failure
events and reports, combined output bounds, concurrent drains, timeout,
cancellation, no infrastructure retry and secret-safe persisted run failure.
Log: ignored `target/harness-tests-phase2-streams-final.log`.

The actual `MavenVerifier(...).verify(...)` was also executed through the repaired
supervisor with a 900-second budget. It invoked the unchanged PowerShell/Wrapper
verification path and accepted fresh reports: **128 unit/startup + 34 integration
tests, zero failures/errors/skips**, packaged JAR, exit 0 in 133.842 seconds.
Evidence: ignored `target/phase2-streams-maven-result.json` and
`target/harness/verify-03479bc4466e4ce7a7156cbd4a0bd4b3/`.
This validates the shared subprocess path locally, not the failed pilot candidate.

The implementation review performed during the repair was self-review, not the
separate independent review required before renewed pilot approval. That later
review is recorded below. Preserve
`PILOT-01` as `needs_review`, attempts 1, with its original run/approval/deadline.
No reconciliation or new approval was written. At that handoff the next gate was
independent trust-root review, followed by agreement on renewed authorization
and preservation of the failed attempt. Do not reset the old counter or
use `resume` to replay it. A successful local suite does not remove that gate.

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

The original documentation follow-up checked only state and text integrity.
The subsequent repair adds fresh local regression evidence above; see the latest
WORKLOG entry for all executed checks. No new model exchange or pilot outcome is
established by those tests.

## Copyable next-session prompt

```text
Read AGENTS.md, WORKLOG.md, docs/IMPLEMENTATION_PLAN.md,
docs/HANDOVER_PHASE2.md, docs/HARNESS.md and ADR 0010.
Inspect PILOT-01 run 43d56cc99c694b6781f51674d0165b69 and its retained worktree.
The task is needs_review after one Malformed Codex JSONL failure; it is incomplete.
The merged-stream defect was reproduced locally and repaired; the discarded pilot
line's exact cause remains unconfirmed. Subsequent independent read-only review
approved repair commit 2ddc658 after an inline-evidence follow-up. Read
docs/handover/pilot-restart-proposal.md for that review and the proposed PILOT-02
state transition; the proposal is not new-attempt authorization.
Do not replay the run, reset counters, broaden scope or promote old product evidence.
After separate owner authorization, apply the proposal's explicit state disposition
and refresh approval against the reviewed new baseline. Until then preserve task
state and do not launch a successor. Batch remains disabled; do not merge,
deploy or delete files.
```

## Subsequent independent review and restart preparation (2026-10-18)

The owner's next instruction, `continue with handover`, selected the next
independent review and concrete restart preparation. Two bounded read-only
`gpt-6-astra` / high review processes examined repair commit
`2ddc658ccb5c50e0b1d6f51d1cdc15173fb6f6ec` against parent `fe54904`.
The first completed review returned a high `evidence_gap` at
`harness/processes.py:1`, without explanatory prose under the restricted schema.
A follow-up supplied the exact diff, complete numbered source, surrounding
callers and existing local verification summaries inline. It returned
`{"verdict":"approve","findings":[]}`; structured schema and scope validation
accepted it. No source changed between those reviews. Safe process metadata and
the inline evidence hash are recorded in the
[restart proposal](handover/pilot-restart-proposal.md).

This is successful real structured read-only reviewer exchange evidence, not
workspace-write implementation or pilot completion. No automatic infrastructure
retry, model fallback, approval refresh, original-run replay or task-state change
occurred. The independent approval applies to the committed repair, not these
later preparatory documentation edits or an unexecuted successor task.

The proposal defines `PILOT-02` with the original four-file scope and criteria,
an explicit blocked disposition for the failed predecessor preserving attempts
and run history, and rewiring only its five direct future dependents. These are
proposed administrative changes, not applied changes. Separate owner authorization
for one new cycle remains required by this handover before recording approval,
reconciling the backlog and executing against a reviewed new base/hash. The
original `PILOT-01` remains `needs_review`, attempts 1; all 11 other tasks remain
pending. Batch remains disabled. See the latest WORKLOG entry for fresh checks.

Fresh `python -B -m unittest discover -s harness/tests -v` passed **63 tests in
135.960 seconds**, zero failures/errors/skips, exit 0; retained log
`target/harness-tests-phase2-review.log`. Maven was not rerun for this
documentation-only follow-up; its earlier repair evidence remains historical.
