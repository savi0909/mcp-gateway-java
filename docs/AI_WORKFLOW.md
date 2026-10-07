# Shared AI implementation and review workflow

## Starting or resuming

Read `AGENTS.md`, the current state/latest entry in `WORKLOG.md`, and `docs/IMPLEMENTATION_PLAN.md`. For code work read the full handoff documents and relevant AC rows. Inspect `git status --short`, existing sources and tests. Work from actual files rather than a previous assistant's completion claims.

The original `docs/IMPLEMENTATION_PROMPT.md` requests the entire milestone. A user asking for a single stage narrows the work to that stage. Do not silently convert a scaffolding request into full implementation. For an explicitly requested full milestone, continue through 1A-1E and do not pause for routine confirmations.

## Implement a stage

1. Identify the stage, remaining behavior, dependency on earlier work and relevant AC identifiers. Explain consequential choices briefly.
2. Inspect resolved framework sources/configuration metadata for API-sensitive changes. Cite official documentation and record actual dependency versions; no guessed APIs.
3. If a choice changes the external contract or documents conflict, ask a focused question and continue independent work. Routine implementation choices can follow the specifications.
4. Implement the smallest useful slice. Keep the coordinator independent, request handling asynchronous, and the catalog/REST/SDK responsibilities clear.
5. Run meaningful tests against mocks; protocol changes require SDK-client integration. Then run Wrapper `verify`. Investigate failures instead of suppressing them or loosening assertions to obtain green output.
6. Inspect the diff. Update plan state and acceptance evidence only for proven behavior. Append a worklog entry with exact commands, results, limitations and next action.
7. Report what changed, what was tested and what remains. Do not commit, push or deploy without an explicit request. Do not delete files without permission.

No project hook invokes tests, model calls or worker allocation automatically. The workflows use repository instructions, shared prompts and normal build commands. They do not require installing external plugins or MCP servers.

## Review a stage

Read the same starting context, inspect the requested diff/files and map behavior to the functional requirements. Do not edit files during a review unless the user explicitly requests fixes. Read-only commands and non-destructive verification builds are allowed.

Prioritize findings with concrete impact: incompatible SDK APIs; empty/duplicate registration; discovery side effects; retries/redirects; unsafe URI resolution; precision loss; secrets in discovery/errors/logs; false allocation outcome claims; invalid Origin handling; tests that bypass MCP or contact the real backend. Report file/line, trigger, impact and suggested fix. Distinguish a defect from an unimplemented later stage.

List actual validation commands/results, missing evidence and residual risks. If no findings are established, say so and identify verification limits. Do not mark acceptance rows complete merely because the code appears plausible.

## Reusable prompts

### Next stage

```text
Read AGENTS.md, WORKLOG.md, docs/IMPLEMENTATION_PLAN.md and all handoff
documents. Implement stage <1A/1B/1C/1D/1E> using docs/AI_WORKFLOW.md.
Preserve the functional specification. Verify the result using the Maven
Wrapper and relevant AC tests. Update the worklog, stage state and evidence.
Ask a focused question when an unresolved requirement affects the result.
```

### Full milestone

```text
Read AGENTS.md, WORKLOG.md and docs/IMPLEMENTATION_PLAN.md, then execute
docs/IMPLEMENTATION_PROMPT.md through the remaining milestone 1 stages.
Build and verify real MCP-to-REST behavior against an independent HTTP mock.
Use the demo contract until the actual coordinator contract is supplied.
Continue through documentation and the SDK smoke client; report actual
versions, test evidence, assumptions and commands. Update the worklog and
acceptance tracker without claiming unverified live-coordinator integration.
```

### Review

```text
Review <stage/diff/files> against docs/FUNCTIONAL_SPEC.md and AGENTS.md.
Use the review workflow in docs/AI_WORKFLOW.md. Inspect actual code and
test evidence. Report prioritized concrete findings with file/line,
impact and relevant AC IDs, plus commands run and missing evidence.
Do not edit files or run live allocations.
```

### Handoff between tools

```text
Prepare a factual handoff in WORKLOG.md: current behavior, changed files,
commands/results, unfinished work, unresolved questions and the next
specific task. Update stage and acceptance states only with evidence.
Do not commit or discard work. The next session may use a different AI tool.
```

## Worklog entry template

```markdown
### YYYY-MM-DD - <tool/person> - <stage/task>

- Scope:
- Changes:
- Decisions/assumptions:
- Verification: `<exact command>` -> <actual result/count or failure>.
- Acceptance evidence: <AC IDs and test names, or no new milestone evidence>.
- Limitations/open questions:
- Next action:
```

Append entries in chronological order. Maintain a short current-state summary at the top. Never put tokens, private raw responses, or allocated live IDs in the worklog.
