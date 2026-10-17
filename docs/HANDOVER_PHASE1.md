# Phase 1 engineering harness handover

Date: 2026-10-17. Scope: the owner requested Phase 1 completion followed by a
handover. Phase 2 pilot and batch have not started. Read [usage](HARNESS.md) and
[ADR 0010](decisions/0010-engineering-harness.md) for the operational contract.

## Read order

1. [AGENTS.md](../AGENTS.md), [WORKLOG current state/latest entry](../WORKLOG.md)
   and [implementation plan](IMPLEMENTATION_PLAN.md).
2. [Phase 0 assessment](handover/repository-assessment.md) and
   [refined plan](handover/refined-autonomous-development-plan.md).
3. [Harness usage](HARNESS.md), ADR 0010, `harness/config.toml`,
   `tasks/schema.json` and `tasks/backlog.json`.
4. The current source/test diff and exact recorded test results; do not treat a
   successful agent summary as independent execution evidence.

The gateway remains a single Java 21/Boot 4.0.8/Spring AI 2.0.1 module with MCP SDK
2.0.0. Static REST adaptation, secured sessions and local delegated UC-01 admission
already exist. No gateway Java source, endpoint, runtime catalog, provider, product
identity policy, dependency version or live service was changed in this phase.
All identity/catalog full-release gaps remain separate from harness readiness.

## Delivered behavior

The standard-library Python runner supplies bootstrap, status, inspect, dry-run,
preflight, single-task and resume commands. Batch always refuses execution. Strict
task contracts require approved exact paths, criteria, source revision and matching
scope hash. A separate pilot authorization remains required. All 12 tasks shipped
in the backlog are pending; none is silently approved by the Phase 1 request.

The runner creates one isolated sibling worktree, starts fresh sandboxed Codex
processes, validates the candidate outside the agent, invokes the actual Wrapper
through a trusted script, checks fresh test reports, then requires a separate
read-only review. Any unresolved finding blocks the pilot. Up to three focused
repairs follow the initial attempt; infrastructure/scope/model failures stop.

One OS lock serializes task ownership. Durable intent, absolute deadlines and
attempt counts survive interruption. Unique Git markers and content hashes recover
the content-commit/metadata-commit gap without replaying implementation. Interrupted
implementation becomes needs-review. Publication failure resumes only publication.
No automatic merge, deletion, cleanup, live tool call or deployment occurs.

Windows descendants enter a Job Object before execution. Timeout/cancellation and
successful parent exit terminate remaining descendants. Logs retain only safe
metadata/finding codes; raw model output is held in bounded memory and discarded.
PowerShell and POSIX wrappers propagate exit codes and reject existing output paths.

## Verification and evidence

Final harness command: `python -B -m unittest discover -s harness/tests -v`.
**PASSED: 49 tests in 98.757 seconds, zero failures/errors/skips**.
Log: ignored `target/harness-tests-final.log`. This supersedes earlier exploratory
and intermediate runs. No live model call or real pilot was part of that suite.
The changed/new text files also passed relative-link/fence, UTF-8/newline/whitespace,
Python AST, JSON and TOML checks where applicable. Enterprise requirements are
unchanged and no gateway `src/` file is part of this change.

Executed gateway command:

```powershell
pwsh -NoProfile -File scripts/verify.ps1 -RunId verify-phase1-20261017
```

It invoked the unchanged Wrapper `verify` lifecycle with
`-Dgateway.build-directory=target/harness/verify-phase1-20261017` and passed:
**128 unit/startup tests + 34 integration tests**, zero failures/errors/skips,
executable JAR packaged. Finished 2026-10-17 16:43:59 +05:30, Maven elapsed 2:04.
The independent report reader accepted all required suites and totals.
Log: ignored `target/phase1-maven-verify.log`.

Executed control checks: bootstrap validates 12 tasks; dry-run finds no approved
ready task and performs zero model calls; preflight reports CLI 0.162.1 and checks
cached supported model IDs/efforts; batch refuses as intended. Actual Codex
implementation/review calls were not made. The installed user configuration already
selects `gpt-6-astra`/high; no active-session model switch or user-wide configuration
change is claimed. Harness model roles explicitly follow the approved plan.

The harness suite uses real child processes, isolated Git repositories and local
bare remotes, with fake agent/reviewer/verifier behavior. It covers ownership,
path and approval rejection; source changes after approval; stale/malformed/failed
Maven reports; bounded repairs; cancellation and descendants; crashed locks;
content/metadata recovery; scope/verification/review tampering; and publication-only
resume. Dependency evidence must exist and its code must be in the approved base.

Exploratory failures are retained honestly: the first 39-test run had 1 failure
and 27 errors due largely to a fixture-root calculation; the corrected-path run
had 4 failures and 14 errors caused by fake edits using CRLF without a fixture
normalization rule. The fixture now resolves under this repository and fake edits
use LF. A later focused lifecycle run passed 16 tests, followed by 46 expanded
tests passing. These earlier counts are superseded by the final result above.

The initial fixture-root error created 40 retained fixture directories under
`D:/ai-projects/target/harness-tests`. They were not deleted or moved. Subsequent
fixtures are under this repository's ignored `target/harness-tests`; retained
worktrees are siblings within that fixture area. Any cleanup requires explicit
owner permission. No long-running harness test process should be needed to resume.

## Phase 1 readiness versus later approval

| Area | Verdict |
| --- | --- |
| Bootstrap/status/dry-run | Implemented; local checks and eligible-task fixture tests verify behavior. The real backlog correctly selects nothing. |
| Single-task harness | Implemented and verified with fake processes, actual Git and failure/recovery cases. |
| Real Codex pilot | Requires separate owner approval, refreshed approved base/hash and actual sandbox/model evidence. Cached model support is not a successful live call. |
| Batch | Disabled in code/configuration; not approved. A successful pilot cannot enable it automatically. |
| Windows verification | Actual Maven verification and Python harness tests executed locally. |
| POSIX | Shell wrapper exercised with Git-for-Windows `sh`; Linux process behavior and hosted CI remain unverified until CI executes. |
| Gateway enterprise release | Unchanged partial/pending identity/catalog acceptance; this engineering phase supplies no new product capability acceptance. |

The existing Windows/Linux CI workflow includes Python 3.13 harness tests before
its existing Java verification. No hosted CI result is claimed here. Worktree
separation and scope checks support trusted repository work; hostile source code,
hostile administrators, power-loss durability and distributed control state remain
outside this local harness contract.

## Next session

Select the actual installed model ID `gpt-6-astra` at High for demanding planning
and review; the observed local CLI configuration already uses it. Do not guess a
model ID from a UI label or claim the active model changed without evidence.

Start with read-only status and the handover:

```powershell
git status --short --branch
python -B -m harness.orchestrator bootstrap
python -B -m harness.orchestrator status
python -B -m harness.orchestrator dry-run
python -B -m harness.orchestrator preflight
```

If the owner explicitly approves Phase 2, select `PILOT-01` (P2-01 in the original
plan): ordinary catalog membership activation through real Boot/SDK tests. Verify
the exact scope/criteria in the backlog, record approval, refresh `base_commit`
and its scope hash, commit approval metadata, then dry-run and execute exactly
that task. Do not use the old Phase 0 SHA as a ready-task base after harness changes.
No live backend, mutation, provider or new identity mode is needed by this pilot.

If the pilot exposes a production defect, preserve its failing test and request
newly approved repair scope. Do not enlarge the allowed source paths automatically.
After the pilot, stop for review before any batch policy change. Preserve worktrees,
logs and unrelated changes; do not run `clean`, reset or deletion commands.

Suggested next-session prompt:

```text
Read AGENTS.md, WORKLOG.md, docs/IMPLEMENTATION_PLAN.md,
docs/HANDOVER_PHASE1.md, docs/HARNESS.md and ADR 0010.
Inspect Git status and the committed harness/test evidence. Phase 1 is complete
within its mocked runner boundary; Phase 2 and batch are not implicitly approved.
Use gpt-6-astra at high reasoning when supported. Preserve the existing Java stack
and all work. Run no real task until its explicit owner approval is recorded.
If Phase 2 is explicitly requested, prepare and run only PILOT-01 through the
documented approval, isolation, independent verification, review and commit cycle.
Stop afterward for review; do not enable batch, merge, deploy or delete files.
```
