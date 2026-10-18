# Claude Code project entry point

@AGENTS.md

The imported file is the shared repository guidance for Claude Code and Codex. Read `WORKLOG.md`, `docs/IMPLEMENTATION_PLAN.md` and `docs/HANDOVER_GATEWAY.md` before choosing the next gateway task. LLM harness work belongs to a separate project; retained harness handovers are historical.

Project skills:

- `/implement-stage <stage and task>` follows `docs/AI_WORKFLOW.md` to implement a bounded stage.
- `/review-stage <stage or files>` follows that document's review workflow and reports evidence without editing files.

Use `CLAUDE.local.md` for personal instructions and `.claude/settings.local.json` for personal settings; both are ignored. Project instructions do not change the user's tool permissions or authorize live allocations.
