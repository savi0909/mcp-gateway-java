---
name: review-stage
description: Review MCP gateway code or a milestone stage against the specification and actual evidence without changing source files.
disable-model-invocation: true
argument-hint: "<stage, diff, or files>"
---

Requested review: $ARGUMENTS

Read `AGENTS.md`, `WORKLOG.md`, `docs/IMPLEMENTATION_PLAN.md`, and the relevant specification/acceptance rows. Follow the **Review a stage** workflow in `docs/AI_WORKFLOW.md` from the repository root. Inspect actual code and report prioritized findings with file/line, impact and missing evidence. Do not edit files or run live allocations. Report verification commands that were actually run.
