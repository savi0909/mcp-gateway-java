# Development workflow

Read `AGENTS.md` and the latest `WORKLOG.md` entry, then pick a scoped task from `docs/IMPLEMENTATION_PLAN.md`. `docs/FUNCTIONAL_SPEC.md` defines behavior; use its AC identifiers in test descriptions and reviews.

1. Inspect the working tree and relevant code before editing. Preserve unrelated work.
2. State the intended behavior and meaningful verification for the change. Resolve uncertainty about the real REST contract through the user; use the documented demo contract for local tests.
3. Implement a small vertical slice with constructor injection and immutable configuration. Add classes only when they have a concrete responsibility.
4. Add behavioral tests using independent HTTP fixtures. Protocol evidence requires a running gateway and real MCP SDK client. Automated tests never target the live coordinator.
5. Run `mvnw.cmd -B -ntp verify` on Windows or `sh ./mvnw -B -ntp verify` on POSIX. Review `git diff --check` and relevant changes.
6. Update the worklog, stage state and acceptance evidence. Record compatibility or architectural deviations in a decision file.

Use `*Test` for unit/startup tests and `*IT` for protocol integration tests. Failsafe is wired into `verify`; `test` alone does not run `*IT`. The current scaffold test should be replaced with real discovery/startup assertions when stage 1C deliberately enables MCP.

Keep catalogs secret-free. The canonical example and packaged default must stay aligned. Do not run Maven `clean` or delete files without explicit permission. Do not add automatic format/test hooks that mutate unrelated files or trigger allocations. Use ignored local settings for credentials and personal tool configuration.

Write commit/PR descriptions around resulting behavior, relevant AC IDs, verification evidence and limitations. A proposed PR template is supplied, but this setup does not commit, publish, or deploy anything.
