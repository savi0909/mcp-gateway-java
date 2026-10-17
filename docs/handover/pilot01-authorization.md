# PILOT-01 owner authorization

Date: 2026-10-17. Authorization source: the owner instructed this session:

> continue with next as per handover at - docs/HANDOVER_PHASE1.md

This instruction selects the handover's next step, Phase 2 PILOT-01 (P2-01),
for one real harness cycle. It follows the completed Phase 1 handover; it does
not authorize batch execution or additional tasks. This record transcribes the
owner's session instruction rather than supplying agent self-approval.

## Approved contract

- Task: `PILOT-01`, prove ordinary catalog membership activation.
- Source revision: `200b7f943b57c44830b37360b95a1abd70c0ab57`.
- Approved task scope SHA-256: `e313afbfb8f83aeae59166845f2baeaf06f3babfa754cdf37652a44ec4e38582`.
- Phase: 2; independent review required; no dependencies.
- Implementation: `gpt-6.1-sol` / medium; review: `gpt-6-astra` / high,
  as pinned in the unchanged harness configuration.

Exact allowed candidate paths:

- `src/test/java/dev/mcp/gateway/admission/Uc01AdmissionIT.java`
- `src/test/java/dev/mcp/gateway/admission/AdmissionFixtures.java`
- `docs/CATALOG_ACCEPTANCE.md`
- `WORKLOG.md`

Acceptance criteria:

- Membership absent: hidden discovery and zero direct business calls.
- Authorized owner activates pinned ordinary membership; same SDK session discovers and executes exactly one read.
- Unauthorized owner cannot activate; scoped before/after audit contains no credential.

The approved source is refreshed from the obsolete Phase 0 SHA. The approval
commit changes only this document and `tasks/backlog.json`. The trusted runner
must enforce the exact scope/hash, use a sibling worktree, run actual Wrapper
`verify` with fresh reports and require a separate read-only review over the
same content before its scoped content/metadata commits and explicit pushes.

## Boundaries and stopping point

Use independent local HTTP mocks and bounded Boot/SDK calls only. No production
Java, dependency, endpoint, identity-mode or credential-policy change is approved.
If the test exposes a production defect, preserve the failing candidate and stop
for new repair scope. Infrastructure/sandbox/model failures stop without retries
or permission bypass. Keep existing harness budgets and repair limits.

The two fresh Codex processes are the prescribed implementation/review cycle;
they must not spawn additional agents. The controlling runner owns all Git
staging, commits and pushes under standing owner authorization. Stop after this
pilot for owner review. Do not merge, enable batch, deploy, delete or clean up.
Preserve worktrees, logs, fixtures and unrelated work.
