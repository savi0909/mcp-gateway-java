# Current MCP gateway handover

Updated 2026-10-28. This is the entry point for continuing this repository.
The owner clarified that this project builds an enterprise-class MCP gateway.
LLM development automation was moved to `D:/Projects/llm-harness`. Read
[ADR 0011](decisions/0011-gateway-project-scope.md). Use gateway requirements
and verified product evidence to choose work here.

For the next bounded task, read [the next-session handover](HANDOVER.md):
S0 protocol/SDK compatibility investigation, with required work and validation.

## Product state

- October 28 gap/sprint planning is complete in
  [GATEWAY_SPRINT_ROADMAP.md](GATEWAY_SPRINT_ROADMAP.md), with a separate
  [modern protocol acceptance plan](MCP_PROTOCOL_ACCEPTANCE.md) and
  [proposed compatibility ADR](decisions/0014-modern-protocol-compatibility-proposal.md).
  Fresh legacy Wrapper `verify` passed 143 unit/startup and 38 integration tests,
  zero failures/errors/skips, in 2:03. Log: `target/roadmap-review-20261028.log`;
  reports/JAR: `target/roadmap-review-20261028`. No new runtime behavior or modern
  protocol conformance is claimed; existing identity/catalog states are unchanged.

- U5 continuation fixes audit-export snapshots under repeated/backward wall
  clocks: opaque cursors bind a scoped append boundary, with current permission
  and expiry checks on every page. Read [ADR 0013](decisions/0013-audit-export-append-boundary.md)
  and the latest WORKLOG verification: 143 unit/startup and 38 Boot/SDK integration
  tests passed, zero failures/errors/skips, Wrapper `verify` in 2:06. Log:
  `target/u5-pagination-final.log`; JAR/reports: `target/u5-pagination-final`.
  U5 remains Partial; workload execution is still unselected.

- Earlier local hardening verification on 2026-10-18: **139 unit/startup and 38
  Boot/SDK integration tests**, zero failures/errors/skips; Wrapper `verify`
  passed in 2:06. Log: `target/admission-hardening-final.log`; reports/JAR:
  `target/admission-hardening-final`. ADR 0012 adds rejection audit, retained
  journal rollover, bounded queueing and audit pagination; U5 remains partial.

- Foundation stages 1A–1E, worker-tool extension and secured foundation are complete
  within their recorded boundaries: SDK-owned MCP, REST adapters, startup catalogs,
  Origin checks, JWT/scopes/tenant/session controls and bounded failure handling.
- Opt-in `workers,secured,uc01` implements local delegated ordinary internal reads:
  separate application/human identity, catalog eligibility, pinned contracts,
  scoped downstream credentials, durable audit before dispatch and local revocation.
- UC-01 U5 and the full enterprise release remain partial. All 32 identity criteria
  and the separate catalog requirements remain release obligations. Workloads,
  sensitive-action approvals, third-party execution and distributed guarantees
  are not established by the current local ordinary-read evidence.
- Fresh post-relocation verification on 2026-10-18: 128 unit/startup plus 34 real
  Boot/SDK integration tests passed against independent mocks, zero failures,
  errors or skips, with Wrapper `verify` and packaged JAR under
  `target/gateway-relocation-20261018`. Log: `target/gateway-relocation-verify.log`.
  No gateway Java changed during the automation work or its removal; this is
  regression evidence, not a new product capability.

## Read order and requirements

1. [AGENTS](../AGENTS.md), [WORKLOG current state/latest entry](../WORKLOG.md),
   [implementation plan](IMPLEMENTATION_PLAN.md).
2. [Detailed UC-01 implementation handover](HANDOVER_UC01.md),
   [UC-01 plan](UC01_ADMISSION_PLAN.md), [guide](UC01_ADMISSION.md),
   [ADR 0009](decisions/0009-uc01-local-admission-design.md).
3. [Identity requirements v1.4](requirements/Enterprise_MCP_Identity_Authorization_Requirements_v1.4.md),
   [catalog requirements v1.3](requirements/Enterprise_MCP_Discovery_Catalog_Requirements_v1.3.md),
   [identity acceptance](IDENTITY_ACCEPTANCE.md), [catalog acceptance](CATALOG_ACCEPTANCE.md).
4. For code, also read [implementation prompt](IMPLEMENTATION_PROMPT.md),
   [foundation specification](FUNCTIONAL_SPEC.md), [design](DESIGN.md),
   [example catalog](../examples/worker-catalog.json), [foundation acceptance](ACCEPTANCE.md).
   Use [enterprise vision](ENTERPRISE_MCP_VISION.md) for future planning.

## Next gateway decision

Use the [October 28 sprint roadmap](GATEWAY_SPRINT_ROADMAP.md) for current
planning. It incorporates completed local hardening and the remaining U5 gaps,
then proposes protocol migration, workloads, identity integration, registry,
native federation, approvals/providers and distributed operations. Its S0 tasks
make the next compatibility investigation concrete; S1 follows with supported
UC-01 lifecycle and composed queued-revocation evidence.

The modern target is MCP `2026-07-28`, distinct from SDK `2.0.0` and Spring AI
`2.0.1`. Newer SDK release notes offer an experimental modern server while
documenting client/header limitations; do not infer a migration version from an
older roadmap or override dependencies without compatibility evidence. S0 can
finish with a precise blocker while protocol-independent work continues.

Read the [2026-10-18 source review and proposed roadmap](GATEWAY_REVIEW_AND_NEXT_FEATURES.md)
for prioritized audit/journal/admission findings, the verified Uber reference,
custom-requirement boundaries and proposed feature packages. The review proposes
UC-01/U5 closure before UC-03, then registry/virtual servers and native federation.
The owner subsequently authorized package A: local rejection audit, retained
journal rollover, bounded admission work, audit pagination and added local
revocation/catalog evidence are implemented under ADR 0012. Read the latest
WORKLOG verification before continuing. Physical audit purge/external archival,
full-release identity modes and distributed guarantees remain outstanding.

The [post-hardening U5 review](UC01_U5_REVIEW.md) separates the corrected export
defect from remaining retention, topology, composed queue/revocation and recovery
obligations. Use it with the latest evidence when selecting the next slice.

Review remaining UC-01/U5 gaps before extending execution modes: physical retention
operations, broader topology administration and release-wide revocation evidence.
The timestamp-only pagination defect found during that review is corrected under
ADR 0013; in-progress hash-only exports need to restart after updating binaries.
The existing detailed handover proposes UC-03
ordinary workload reads and mixed-context isolation after that review. Select
a concrete gateway slice with its requirements and acceptance evidence before
implementation. No new feature is selected by this context correction alone.

Use the Maven Wrapper directly and independent random-port mocks. Preserve
Java 21, Boot 4.0.8, Spring AI BOM 2.0.1 and the BOM-managed SDK. Keep unsupported
sensitive reads/mutations/autonomous/third-party execution disabled unless their
own product scope is explicitly selected and implemented. Keep standing no-delete,
no-live-allocation, no-deployment and commit/push rules.

## Separate development automation

The unrelated automation work from October 17–18, its original documentation and
local evidence were moved to `D:/Projects/llm-harness/imports/mcp-gateway-engineering-harness`.
That work added no gateway product capability. Its task state is not a gateway
implementation gate or product acceptance result.

## Copyable continuation prompt

```text
Read AGENTS.md, WORKLOG.md, docs/IMPLEMENTATION_PLAN.md and
docs/HANDOVER_GATEWAY.md. This project is the MCP gateway. LLM harness work is
a separate project; do not run or extend its workflow here.
Review UC-01/U5 gaps and the product requirements/acceptance trackers. Implement
only the requested gateway slice. For next-sprint planning, read
docs/GATEWAY_SPRINT_ROADMAP.md, docs/MCP_PROTOCOL_ACCEPTANCE.md and proposed
ADR 0014; S0 is the recommended first compatibility investigation. Preserve the
foundation and pinned stack, use
independent mocks and real SDK checks where needed, run appropriate Wrapper
verification, and update factual evidence before committing and pushing.
```
