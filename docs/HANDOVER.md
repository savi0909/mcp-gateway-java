# Next-session handover: review S2 storage contract before S4

Updated: 2026-10-28. Repository: `D:/ai-projects/mcp-gateway-server-java`.
The owner subsequently requested S2 contract plus implementation and tests.
That non-destructive local increment is documented in
[the storage contract](S2_STORAGE_CONTRACT.md) and
[ADR 0016](decisions/0016-offline-audit-checkpoint-and-restore.md).
Read WORKLOG and [the S2 evidence report](S2_RECOVERY_EVIDENCE.md) for final checks:
152 unit/startup and 62 integration tests, zero failures/errors/skips, Wrapper
verify in 4:03 under `target/s2-reviewed`. Next gate: owner review
of that contract before separately selecting S4. No physical deletion is authorized;
physical expiry, production archive durability and distributed recovery remain
incomplete. Historical S1/S2 planning text below is preserved as context.
S1 is complete for the selected local ordinary-read scope. Read
[S1 evidence](S1_LIFECYCLE_EVIDENCE.md) and [ADR 0015](decisions/0015-uc01-lifecycle-and-control-capacity.md).
Full Wrapper verify: 144 unit/startup + 60 integration tests, zero failures/errors/skips,
BUILD SUCCESS in 3:34; `target/s1-reviewed.log` and `target/s1-reviewed`. Thirteen control
changes complete in 17–52 ms under a full 256-slot business queue; 26 actual
subscribed SDK calls deny at fresh revision 2, zero downstream calls/output.

Read the [S2 functional requirements draft](S2_AUDIT_LIFECYCLE_REQUIREMENTS.md)
for the pending acceptance matrix and decisions required before implementation.

Next proposed task: design S2's bounded audit lifecycle/recovery contract from the
[sprint roadmap](GATEWAY_SPRINT_ROADMAP.md): archive/checkpoint/restore ownership,
integrity manifests, capacity/failure handling and attributable lifecycle evidence.
Review retention preservation and uncertain outcomes before implementation.
Physical expiry requires its operational contract and explicit deletion permission;
none is granted here. Keep modern MCP deferred and pins unchanged. Do not begin
S4 or other execution modes as part of S2. The completed S1 scope is retained below
as historical context; this handover does not authorize S2 implementation by itself.

The owner explicitly confirmed that modern MCP migration should be deferred
while functional components are built. S1 used the pinned runtime; modern
support is not an S1 completion gate. S2 audit lifecycle/recovery and S4 workload
identities are the proposed following priorities, with their own scoped gates.
Revisit modern compatibility before modern native federation or modern-client
commitments; see the priority decision in ADR 0014 and the sprint roadmap.

## S0 starting state (historical)

- S0 starting baseline: commit `0df6579` on `main`. Read
  [compatibility findings](S0_COMPATIBILITY_FINDINGS.md),
  [normative inventory](MCP_2026_REQUIREMENTS.md) and accepted
  [ADR 0014](decisions/0014-modern-protocol-compatibility-proposal.md).
- Isolated direct SDK probes: 14 passing tests. AI 2.0.2 and 2.1.0-RC1 starter
  probes: one passing legacy test each. The preview lacks modern Java client and
  modern WebFlux binding; conflicting duplicate/custom headers can reach execution.
  Keep all product dependencies pinned. All modern MP rows remain Pending.
- Runtime: Java 21, Spring Boot 4.0.8, Spring AI BOM 2.0.1 and BOM-managed Java
  SDK 2.0.0. Tested MCP revision: `2025-11-25`; proposed modern target: `2026-07-28`.
- Latest product Wrapper `verify`: 143 unit/startup and 38 integration tests, zero
  failures/errors/skips, BUILD SUCCESS in 2:01. Evidence:
  `target/s0-product-regression.log` and `target/s0-product-regression`.
  These are independent mock and real Boot/SDK results for the current runtime.
- Modern protocol rows remain Pending; ADR 0014 retains current pins. Enterprise identity
  and catalog acceptance remain partial/pending within their documented scopes.

## Read first

1. [AGENTS.md](../AGENTS.md), [WORKLOG.md](../WORKLOG.md),
   [implementation plan](IMPLEMENTATION_PLAN.md) and
   [current gateway handover](HANDOVER_GATEWAY.md).
2. [Sprint roadmap](GATEWAY_SPRINT_ROADMAP.md), especially S1 and its concrete
   lifecycle/revocation exit evidence; [S0 findings](S0_COMPATIBILITY_FINDINGS.md).
3. [UC-01 handover](HANDOVER_UC01.md), [plan](UC01_ADMISSION_PLAN.md),
   [guide](UC01_ADMISSION.md), [U5 review](UC01_U5_REVIEW.md), ADRs 0009/0012/0013.
4. [Identity requirements v1.4](requirements/Enterprise_MCP_Identity_Authorization_Requirements_v1.4.md),
   [identity acceptance](IDENTITY_ACCEPTANCE.md),
   [catalog requirements v1.3](requirements/Enterprise_MCP_Discovery_Catalog_Requirements_v1.3.md)
   and [catalog acceptance](CATALOG_ACCEPTANCE.md).
5. For code: implementation prompt, functional spec, design, example catalog and
   foundation acceptance; inspect admission administration, policy/cache, work queue,
   journals, identity mapping, filters and current protocol test fixtures.

## Completed S1 required work (historical scope)

1. Implement authorized organization/tenant lifecycle administration and selective
   multi-tenant membership changes within existing UC-01. Changes affect only the
   selected memberships; publication/membership alone cannot grant execution.
2. Prove stable authority/subject identity behavior across claim/display changes.
   Define and implement versioned timing/policy-default administration consistent
   with identity v1.4 and current 30-second verified-policy fallback.
3. Compose real Boot/SDK calls queued behind controlled admission work with
   administrative revocation. Record policy revision, queued-call identifiers,
   accepted/effective times, admission order and downstream counters.
4. Revoked/expired queued work must receive fresh checks and produce zero business
   execution/protected output. Demonstrate revocation within 60 seconds and no
   stale authority beyond 30 seconds. If queue saturation blocks administration,
   provide bounded control-path capacity or priority rather than a supplier-only test.
5. Add meaningful functional/protocol tests, preserve durable audit before dispatch,
   update only acceptance obligations actually evidenced, append WORKLOG and handoff.

## Boundaries and validation

Keep S0 experimental builds isolated from the pinned product runtime. SDKs own
protocol handling; do not implement a handwritten JSON-RPC server/controller.
Use independent local fixtures, random ports and bounded waits. Preserve the
existing demos, coordinator and enterprise admission guarantees.

Do not deploy, allocate live IDs, run `clean`, use destructive Git operations or
spawn subagents. Any file/directory deletion requires explicit user permission.
Do not promote identity/catalog acceptance without complete applicable evidence.
Keep secrets out of source, logs and handovers.

Keep workloads, sensitive reads/mutations, autonomous and third-party execution
disabled. Physical retention/archive/restore is S2 and requires its own contract;
actual file deletion still needs explicit permission. Run appropriate checks. Product
Java/configuration changes require full Maven Wrapper `verify`; documentation
changes require link/format checks. Label experiment results separately from
product conformance. Commit and push completed repository work under the standing
authorization; update this handover with the next concrete task.

## Completed S1 and following task

S1 lifecycle/selective membership, stable identity, versioned timing and actual
queued-call revocation are implemented and verified; see the linked evidence. The next proposed slice is S2's
audit lifecycle/recovery design, with physical expiry still subject to a concrete
operational contract and explicit deletion authorization. Modern S3/S7 stay blocked
until official client/binding/header support satisfies ADR 0014.

## Start the next session

Tell the agent: **Read `docs/HANDOVER.md` and continue the next task.**
