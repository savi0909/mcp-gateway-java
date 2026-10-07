# Repository instructions for coding agents

## Read first

Read this file, `WORKLOG.md` (current state and latest entry), and `docs/IMPLEMENTATION_PLAN.md` before changing code. For implementation, read `docs/IMPLEMENTATION_PROMPT.md`, `docs/FUNCTIONAL_SPEC.md`, `docs/DESIGN.md`, and `examples/worker-catalog.json`; use `docs/ACCEPTANCE.md` to find required evidence.

Explicit user instructions take precedence. `docs/FUNCTIONAL_SPEC.md` is authoritative for milestone behavior; `docs/DESIGN.md` guides architecture; `docs/IMPLEMENTATION_PROMPT.md` describes the complete implementation request. Repository workflow files do not relax the functional requirements. Ask a focused question if documents conflict or an unresolved choice affects the external contract. Continue independent work while waiting.

## Current development state

This is a single-module gateway with validated properties/catalogs, a bounded REST executor, ASYNC tools, Origin enforcement, independent HTTP fixtures and an SDK smoke client. Default MCP remains disabled. Opt-in profiles `payments`, `workers`, and `worker-payments` expose one, nine or ten tools; `mock` supplies the standalone worker demo. Tests were explicitly resumed: full Wrapper verification passed with 121 unit/startup tests and 20 integration tests, all using independent local mocks. The `secured` profile adds JWT callers, explicit scopes, tenant admission and session-owner enforcement (ADR 0006). The existing running demo was left unchanged; new artifacts are under `target/foundation`. Consult `WORKLOG.md` and ADRs 0002–0004/0006 for evidence and limits.

Use Java 21, Spring Boot 4.0.8, Spring AI BOM 2.0.1, Maven 3.9.11 via Wrapper, and package `dev.mcp.gateway`. Inspect resolved dependency sources and configuration metadata before coding against SDK or starter APIs. Keep the BOM-managed SDK version; do not invent constructors, handlers, or property names. Version changes require official compatibility evidence and a decision record.

## Commands

| Task | PowerShell | POSIX shell |
| --- | --- | --- |
| Verify and package | `.\mvnw.cmd -B -ntp verify` | `sh ./mvnw -B -ntp verify` |
| Unit/startup tests | `.\mvnw.cmd -B -ntp test` | `sh ./mvnw -B -ntp test` |
| One test | `.\mvnw.cmd -B -ntp '-Dtest=ScaffoldStartupTest' test` | `sh ./mvnw -B -ntp -Dtest=ScaffoldStartupTest test` |
| Run locally | `.\mvnw.cmd spring-boot:run` | `sh ./mvnw spring-boot:run` |
| Resolve dependency tree | `.\mvnw.cmd -B -ntp dependency:tree` | `sh ./mvnw -B -ntp dependency:tree` |

PowerShell environment check: `pwsh -NoProfile -File scripts/doctor.ps1`. Tests named `*Test` run with Surefire; future protocol tests named `*IT` run with Failsafe during `verify`. Run `verify` before handing off Java/configuration changes. A passing startup test is not proof of MCP interoperability.

If an existing Windows process holds the default JAR open, use
`.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' verify` to run
all checks/package independently. Do not restart an existing demo solely to build.
CI executes tests again. SDK graceful client closure does not guarantee cancellation
of outstanding requests; local disposal/physical HTTP reset and total deadlines
have separate executed evidence. Tenant admission does not make backend storage
tenant-aware; caller JWTs never become downstream bearer tokens.

## Required boundaries

- Keep the existing Worker Coordinator an unchanged REST service. Only the gateway depends on MCP/Spring AI.
- One virtual server `worker-coordinator`, fixed endpoint `/worker-coordinator/mcp`, loopback listener. Legacy catalog versions 1/2 have one tool; user-directed version 3 supports 1–32 tools with explicit body/path mappings (ADR 0004). Fail on unsupported definitions.
- SDK owns protocol negotiation, lifecycle, sessions, envelopes and unknown tools. No handcrafted JSON-RPC or REST controller impersonating MCP.
- Catalog owns public metadata and private static REST binding; deployment configuration owns the backend origin, budgets and optional token. Never expose bindings or secrets through discovery.
- Validate the catalog once at startup into immutable records. Enable MCP only once validation and registration work together; revise the scaffold test to assert the completed behavior at that stage.
- Use constructor injection, a small package tree, `WebClient`, and ASYNC SDK handlers. No blocking calls or `.block()` in request handlers.
- User-directed application retries reuse exact invocation values within one total deadline (ADR 0002). No connector retries, redirects, allocation-result cache, generated result IDs, or discovery/startup allocation calls. Generate only the payment request key; worker owner UUIDs come from callers.
- Preserve string IDs exactly and integral JSON values without floating-point loss. Legacy tools project only their ID field; version-3 coordinator tools project exactly their advertised response fields, including lease epoch/ownership/expiry (ADR 0004).
- Execution errors are sanitized SDK tool results; error structured content is absent. Upstream failures report `allocationOutcome: unknown`; never promise rollback or recommend automatic retry.
- Enforce Origin before execution. Do not substitute permissive CORS headers for Origin validation.
- No model starter/key, database, Nacos, Alibaba starter, Spring Cloud Gateway, plugin framework, hot reload, native MCP proxying, or extra virtual endpoints in milestone 1.

## Working and handoff rules

Work in the stage order in `docs/IMPLEMENTATION_PLAN.md`; implement only the requested scope. When asked for the full milestone, continue through all stages without routine confirmation. Explain consequential choices briefly so the user can learn the code.

Preserve the supplied handoff documents and unrelated changes. Do not delete files or directories, run `clean`, reset/discard work, or use destructive Git commands without explicit user permission. The user's standing instruction is to commit completed repository work and push to `origin` after the relevant checks; no repeated confirmation is needed. Do not publish releases, deploy, or modify the live coordinator unless requested. Do not spawn additional agents unless requested.

Use independent local HTTP mocks and random ports for tests; never allocate live IDs in automated tests. Real allocation requires an explicitly requested live check. Verify protocol behavior through an actual running Boot server and compatible SDK client; direct dispatcher tests are supplementary. Keep waits bounded and prove no retries using request counters. Add meaningful tests for functional changes; documentation edits do not need mirror tests.

Keep secrets out of source, catalogs, logs, worklogs and prompts. `.env.example` is reference text; Boot does not load `.env` automatically. Keep personal overrides in ignored files.

At handoff, append a factual entry to `WORKLOG.md`, update the plan and acceptance tracker only where evidence exists, and record significant decisions under `docs/decisions/`. Report changed behavior, exact commands/results, limitations and next action. Never claim a test ran when it did not; distinguish mock evidence from live evidence. If interrupted, record in-progress work and unresolved failures so either AI tool can resume.
