# ADR 0011: Keep gateway development separate from LLM automation

Date: 2026-10-18. Status: accepted; explicit owner direction.

## Context

Engineering harness work on 2026-10-17 and 2026-10-18 added an automation runner,
its pilot/repair/review records and Python checks. Its guidance became prominent
in AGENTS, the worklog, implementation plan and development navigation. Resuming
the latest handover therefore selected automation rather than MCP gateway work.
No gateway Java or Maven product change was delivered in those two days.

The owner clarified: "in future do not work on llm harness its a seprate project"
and requested validation of project documentation to prevent wrong context.

## Decision

- This repository's active project is the Java MCP gateway and its product tests,
  requirements, documentation and deployment/build verification.
- Do not develop, repair, run or extend the retained LLM engineering harness,
  launch its pilot/batch, refresh approvals or use its phase numbers as gateway
  milestones. This supersedes ADR 0010's active workflow for this repository.
- Resume through [the gateway handover](../HANDOVER_GATEWAY.md), the product
  implementation plan and foundation/identity/catalog evidence. Historical
  `tasks/backlog.json` is not the gateway product task authority.
- Preserve existing harness code, backlog, approval/run state, fixtures and
  worktrees. Historical documents receive explicit notices. No deletion, move,
  extraction or creation of a separate repository is authorized by this decision.
- Gateway CI runs its existing Windows/Linux Maven verification without harness
  Python setup or test execution. No runtime Java, dependencies or product
  acceptance state changes as part of this scope correction.

## Consequences

The stopped harness pilot and proposed successor do not block gateway maintenance.
Product scope still comes from the owner's request and existing requirements;
this correction does not silently approve UC-03, federation or a full release.
Historical test counts remain evidence for the revisions and boundaries recorded,
not claims that current services or hosted CI have been checked.
