# Next-session handover: S0 protocol compatibility investigation

Updated: 2026-10-28. Repository: `D:/ai-projects/mcp-gateway-server-java`.
Next task: execute the bounded S0 design/compatibility investigation from the
[sprint roadmap](GATEWAY_SPRINT_ROADMAP.md). Complete S0 before implementing
the modern protocol migration.

## Current state

- Planning baseline: commit `52b59d5` on `main`, pushed to `origin/main`.
- Gap analysis, S0-S11 sprint plan, protocol acceptance plan and proposed
  compatibility ADR are complete. No modern compatibility probe has run.
- Runtime: Java 21, Spring Boot 4.0.8, Spring AI BOM 2.0.1 and BOM-managed Java
  SDK 2.0.0. Tested MCP revision: `2025-11-25`; proposed modern target: `2026-07-28`.
- Latest Wrapper `verify`: 143 unit/startup and 38 integration tests, zero
  failures/errors/skips, BUILD SUCCESS in 2:03. Evidence:
  `target/roadmap-review-20261028.log` and `target/roadmap-review-20261028`.
  These are independent mock and real Boot/SDK results for the current runtime.
- Modern protocol rows remain Pending; ADR 0014 is Proposed. Enterprise identity
  and catalog acceptance remain partial/pending within their documented scopes.

## Read first

1. [AGENTS.md](../AGENTS.md), [WORKLOG.md](../WORKLOG.md),
   [implementation plan](IMPLEMENTATION_PLAN.md) and
   [current gateway handover](HANDOVER_GATEWAY.md).
2. [Sprint roadmap](GATEWAY_SPRINT_ROADMAP.md), especially S0-01 through S0-05.
3. [Protocol acceptance plan](MCP_PROTOCOL_ACCEPTANCE.md).
4. [Proposed ADR 0014](decisions/0014-modern-protocol-compatibility-proposal.md).
5. [Dependency baseline](DEPENDENCY_BASELINE.md) and relevant transport/security
   source, including `FilteredMcpTransport` and the session-owner filters.

## Required work

1. Define supported inbound/outbound revisions and capabilities. Enumerate
   applicable normative requirements beyond the grouped protocol acceptance rows.
2. Recheck current official SDK releases, published artifacts and Spring
   compatibility. Earlier research found an outdated SDK roadmap pointing to
   3.x, while 2.2.0-M1 release notes already offered a modern server with
   client/header limitations. Verify current facts rather than selecting that
   prerelease by assumption. Official source links are in ADR 0014 and WORKLOG.
3. Inspect exact candidate source APIs, configuration metadata and dependency
   trees, including Reactor/Jackson compatibility and both server/client support.
4. Create isolated executable probes through official SDKs: modern server
   discovery, tool listing and one ordinary read; two authorized caller contexts;
   header/body mismatch rejection; outbound modern client against an independent
   upstream; and legacy compatibility, streaming and cancellation where supported.
5. Record a reproducible recommendation: exact viable dependency combination,
   starter integration, coexistence/migration strategy, blockers and risks.
   Update ADR 0014, protocol evidence and WORKLOG with actual commands/results.

## Boundaries and validation

Keep experimental builds isolated from the pinned product runtime. SDKs own
protocol handling; do not implement a handwritten JSON-RPC server/controller.
Use independent local fixtures, random ports and bounded waits. Preserve the
existing demos, coordinator and enterprise admission guarantees.

Do not deploy, allocate live IDs, run `clean`, use destructive Git operations or
spawn subagents. Any file/directory deletion requires explicit user permission.
Do not promote identity/catalog acceptance without complete applicable evidence.
Keep secrets out of source, logs and handovers.

Run checks appropriate to the probes and any repository changes. Product
Java/configuration changes require full Maven Wrapper `verify`; documentation
changes require link/format checks. Label experiment results separately from
product conformance. Commit and push completed repository work under the standing
authorization; update this handover with the next concrete task.

## Completion and following task

S0 is complete when its compatibility findings and recommendation are reproducible.
A precise dependency blocker is a valid S0 outcome, while modern support and
S3/S7 readiness remain pending. A server-only probe cannot establish outbound
federation compatibility.

Complete S0 only. If migration is blocked, identify how protocol-independent
work can continue. The next proposed product slice is **S1: UC-01 lifecycle and
composed queued-revocation closure**, as defined in the sprint roadmap.

## Start the next session

Tell the agent: **Read `docs/HANDOVER.md` and continue the next task.**
