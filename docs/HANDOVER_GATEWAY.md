# Current MCP gateway handover

Updated 2026-10-18. This is the entry point for continuing this repository.
The owner clarified that LLM development automation is a separate project.
Read [ADR 0011](decisions/0011-gateway-project-scope.md). Do not resume the
historical harness Phase 1/2 handovers or proposed pilot here.

## Product state

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
- Last recorded gateway verification: 128 unit/startup plus 34 real Boot/SDK
  integration tests passed against independent mocks during the 2026-10-18
  subprocess repair checks. No gateway Java changed on October 17–18; these were
  regression checks, not new gateway capabilities. No fresh Maven run is claimed
  for this documentation/CI separation.

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

Review remaining UC-01/U5 gaps before extending execution modes: pre-identity
authentication-denial audit, controlled retention/archival, broader revocation
and catalog activation evidence. The existing detailed handover proposes UC-03
ordinary workload reads and mixed-context isolation after that review. Select
a concrete gateway slice with its requirements and acceptance evidence before
implementation. No new feature is selected by this context correction alone.

Use the Maven Wrapper directly and independent random-port mocks. Preserve
Java 21, Boot 4.0.8, Spring AI BOM 2.0.1 and the BOM-managed SDK. Keep unsupported
sensitive reads/mutations/autonomous/third-party execution disabled unless their
own product scope is explicitly selected and implemented. Keep standing no-delete,
no-live-allocation, no-deployment and commit/push rules.

## What October 17–18 accomplished

October 17: repository assessment and engineering automation runner, isolated
task execution, verification/review/recovery controls and mocked tests.
October 18: first real runner attempt failed; stream handling was repaired,
63 harness tests passed and independent code review approved the repair. The
planned gateway membership-activation test was not delivered by that attempt.
These were automation activities; the gateway product implementation remained
unchanged. Preserve their records as history while continuing gateway work here.

## Copyable continuation prompt

```text
Read AGENTS.md, WORKLOG.md, docs/IMPLEMENTATION_PLAN.md and
docs/HANDOVER_GATEWAY.md. This project is the MCP gateway. LLM harness work is
a separate project; do not run or extend its retained code or pilot workflow.
Review UC-01/U5 gaps and the product requirements/acceptance trackers. Implement
only the requested gateway slice, preserve the foundation and pinned stack, use
independent mocks and real SDK checks where needed, run appropriate Wrapper
verification, and update factual evidence before committing and pushing.
```
