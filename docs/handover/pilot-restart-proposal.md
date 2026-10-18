# Proposed pilot restart after reviewed process-stream repair

> Historical, unapproved proposal. The owner's subsequent 2026-10-18 direction
> placed LLM harness work in a separate project. Do not apply this state transition
> or launch PILOT-02 here. Use [the gateway handover](../HANDOVER_GATEWAY.md)
> and [ADR 0011](../decisions/0011-gateway-project-scope.md). Preserve existing state.

Date: 2026-10-18. Status: **proposal; not authorization**. The owner's instruction
`continue with handover` selected independent review and restart preparation.
It does not supply the separate new-attempt authorization required by the
[Phase 2 handover](../HANDOVER_PHASE2.md). No backlog, approval or run state has
been changed by this proposal.

## Reviewed source and evidence

The bounded adapter repair is commit
`2ddc658ccb5c50e0b1d6f51d1cdc15173fb6f6ec`, reviewed against parent
`fe54904b6cecbf9cd9a11f1aad5200c99995334c`. The existing configured reviewer,
`gpt-6-astra` / high, ran through `Codex.execute(..., review=True)` with a
read-only sandbox, no inherited user configuration and a 600-second budget.

The first completed review returned one high `evidence_gap` at
`harness/processes.py:1`; its restricted report did not explain the gap. It is
not an approval. A follow-up supplied the complete numbered source, surrounding
callers, exact diff and existing verification summaries inline. That review
returned `{"verdict":"approve","findings":[]}` and passed `review_report`.
No source changed between reviews. This was an evidence follow-up after a valid
review response, not an automatic retry of a failed process or model fallback.

| Review | Exit / seconds | Stdout / stderr bytes | JSONL events |
| --- | --- | --- | --- |
| Initial evidence-gap report | 0 / 27.063 | 691 / 989 | 5 |
| Inline-evidence approval | 0 / 23.757 | 384 / 0 | 4 |

Local safe reports are retained at
`target/phase2-repair-independent-review.json` and
`target/phase2-repair-independent-review-evidence.json`. The supplied inline
payload SHA-256 is
`1771c6783d8bff9fa42ff87584053c1681bbd3b7abcc4909a8403043b456d3ea`.
Raw child stdout/stderr and arbitrary reviewer prose were not persisted. The
review establishes a successful structured read-only reviewer exchange; it does
not establish workspace-write sandbox execution, a successful pilot, hosted CI
or Linux behavior. The later documentation in this proposal is outside that
code-review verdict.

## Concrete state transition for separate owner authorization

Use a new task ID, `PILOT-02`, for the same ordinary membership activation
acceptance criteria. Preserve the failed execution as history rather than
changing its attempts, deadline or completion evidence.

| Record | Proposed change after authorization |
| --- | --- |
| `PILOT-01` backlog task | Set status to `blocked`, with a reason identifying the infrastructure stop and proposed successor `PILOT-02`. Retain attempts 1, original run ID, base, approval and null completion fields. Never mark completed. |
| Original durable run and worktree | Leave byte-for-byte unchanged, including run stage `needs_review`, deadline, event and original worktree HEAD. Do not invoke `resume`. The backlog status change is an explicit administrative disposition; the run retains its historical execution stage. |
| New `PILOT-02` task | Copy the exact scope and acceptance criteria below; phase 2, no execution dependencies, model role `implement`, independent review required, attempts 0, null run/content/evidence fields. Set `ready` only with newly recorded owner approval. |
| Future dependencies | Replace only the direct `PILOT-01` dependency with `PILOT-02` in `AUDIT-01`, `AUDIT-03`, `IDENTITY-01`, `CATALOG-01` and `REVOKE-01`. They remain pending and unapproved; preserve their other fields and all indirect dependencies. |
| Approval input | Add `docs/handover/pilot02-authorization.md`, point `pilot_authorization` to it, use the reviewed current published HEAD as the new source base, and compute the new task hash with `harness.common.scope_hash`. Commit only the approval document and backlog after that source base. |

`PILOT-02` has no dependency on the unsuccessful task because the harness requires
independently completed dependency evidence. Its lineage is recorded explicitly
in the new approval document and the old task's block reason. Pending later tasks
must require the eventual verified successor, never a falsely completed predecessor.
This uses existing status/schema and replay guards; no runner-policy change is
proposed. The exact new base/hash must be calculated after these preparatory
documents are published, not copied from the obsolete `PILOT-01` approval.

## Exact candidate scope and acceptance

Allowed candidate paths remain:

- `src/test/java/dev/mcp/gateway/admission/Uc01AdmissionIT.java`
- `src/test/java/dev/mcp/gateway/admission/AdmissionFixtures.java`
- `docs/CATALOG_ACCEPTANCE.md`
- `WORKLOG.md`

Acceptance criteria remain:

- Membership absent: hidden discovery and zero direct business calls.
- Authorized owner activates pinned ordinary membership; the same SDK session
  discovers and executes exactly one read.
- Unauthorized owner cannot activate; scoped before/after audit contains no credential.

Run exactly one cycle through the controlling checkout's trusted harness:
dry-run, isolated implementation, independent Wrapper verification, separate
read-only review, task-branch content commit and controlling-branch metadata
commit, with explicit pushes under standing authorization. The configured
implementation remains `gpt-6.1-sol` / medium; review remains `gpt-6-astra` / high.
Keep existing deadlines, repair limits and immediate infrastructure stops.

Use independent local mocks only. No production Java, build/CI, harness,
dependency, endpoint, credential-policy or live-service change is included.
If the pilot reveals a production defect, retain the failing candidate and stop
for new scope. Stop after the cycle for owner review. Do not merge, enable batch,
deploy, delete, clean up or spawn additional agents.

## Next-session instruction

Read this proposal and the Phase 2 handover. Confirm separate owner authorization
for one `PILOT-02` cycle before applying the table above. Recheck clean controlling
HEAD, unchanged failed run/worktree, published repair review and current exact
base/hash. Commit approval metadata, run bootstrap/preflight/dry-run, then execute
only `PILOT-02`. Preserve the unsuccessful predecessor and all artifacts.
