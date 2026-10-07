# MCP Gateway Server

A local Java MCP gateway exposing the existing coordinator's worker lease and namespace APIs alongside its separate payment sample. The coordinator manages leases, the payment worker generates payment IDs, and the gateway adapts REST and MCP.

**Current state:** validated configuration, bounded REST execution, strict catalogs, ASYNC registration, Origin enforcement and an SDK smoke client are implemented. Default MCP is disabled. The running `worker-payments` profile exposes nine coordinator tools plus `create_sample_payment`; actual SDK discovery listed all ten. Wrapper `verify -DskipTests` passed, including test compilation. Automated test execution remains paused; worker invocations have not yet been verified.

See [Worker tools and definitions](docs/WORKER_TOOLS.md) for tools, schemas and run commands, and [the payment demo](docs/PAYMENT_DEMO.md) for Docker setup. The running MCP address is `http://127.0.0.1:8080/worker-coordinator/mcp`.

## Start development

Requires Java 21. The Maven Wrapper pins Maven 3.9.11; its first use needs network access to Maven Central. No model-provider API key is needed by the application.

PowerShell, from the repository root:

```powershell
pwsh -NoProfile -File scripts/doctor.ps1
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd spring-boot:run
```

On Linux/macOS:

```sh
java -version
sh ./mvnw -B -ntp verify
sh ./mvnw spring-boot:run
```

The default development process binds to `127.0.0.1:8080`. Stop it with Ctrl+C. `/worker-coordinator/mcp` returns 404 with the default disabled profile; the opt-in tool profiles enable it. Tests start Boot on a random loopback port. Run the executable artifact with:

```powershell
java -jar target/mcp-gateway-server-0.1.0.jar
```

## Implement with Codex or Claude Code

Open the project root in your AI coding tool. Codex receives repository guidance from [AGENTS.md](AGENTS.md); Claude Code uses [CLAUDE.md](CLAUDE.md), which imports the same guidance. Both have `implement-stage` and `review-stage` project skills and use the same workflow.

```powershell
codex
# Or, in a separate session:
claude
```

Use the current worklog to resume with either tool:

```text
Read AGENTS.md, WORKLOG.md, docs/IMPLEMENTATION_PLAN.md and the supplied
handoff documents. Preserve the user-directed worker tools, payment demo and retry decisions.
When the user resumes tests, finish the revised mock/catalog/SDK tests and
Wrapper verify. Update the worklog and acceptance evidence.
```

For Claude Code you can use `/implement-stage 1B` or `/review-stage 1B`. In Codex, ask it to use the `implement-stage` or `review-stage` skill. [AI_WORKFLOW.md](docs/AI_WORKFLOW.md) contains prompts for staged development, full implementation, review and handoff. Keep one active writer in a checkout; hand off through the worklog before switching tools.

AI coding tools require their own login/subscription or credentials; that is separate from the gateway, which has no model dependency. No tool permissions, global configuration, external MCP connections, or automatic allocation hooks are installed by this scaffold.

## Project map

| Path | Purpose |
| --- | --- |
| `FUNCTIONAL_SPEC.md` | Authoritative behavior and AC-01 through AC-13 |
| `DESIGN.md` | Component boundaries and architecture guidance |
| `IMPLEMENTATION_PROMPT.md` | Original full milestone implementation request |
| `AGENTS.md`, `CLAUDE.md` | Shared AI guidance and Claude entry point |
| `WORKLOG.md` | Verified state, work history, blockers and next action |
| `docs/IMPLEMENTATION_PLAN.md`, `docs/ACCEPTANCE.md` | Stages and evidence tracker |
| `docs/decisions/` | Significant implementation choices |
| `docs/DEPENDENCY_BASELINE.md` | Resolved versions, source/API inspection and compatibility evidence |
| `.agents/skills/`, `.claude/skills/` | Tool-specific entry points for shared workflows |
| `src/main/java/dev/mcp/gateway/` | Configuration, catalog validator, REST executor, MCP registration/filter and SDK smoke client |
| `src/main/resources/application.yml` | Validated local defaults; MCP disabled by default |
| `src/main/resources/application-payments.yml`, `examples/payment-catalog.json` | Opt-in payment-only profile and original tool definition |
| `src/main/resources/application-workers.yml`, `examples/coordinator-catalog.json` | Nine worker/namespace tools and explicit REST mappings |
| `src/main/resources/application-worker-payments.yml`, `examples/coordinator-payment-catalog.json` | Combined ten-tool profile with separate coordinator/payment origins |
| `compose.coordinator.yml` | Separate unchanged coordinator, payment worker and private PostgreSQL |
| `examples/worker-catalog.json` | Original catalog restored from the supplied ZIP |
| `src/main/resources/catalog/worker-catalog.json` | Matching packaged default catalog |
| `src/test/java/` | Default startup guard, independent HTTP fixture and adapter/configuration tests; final verification deferred |
| `scripts/doctor.ps1` | Read-only local prerequisite check |
| `.github/workflows/verify.yml` | Maven verification on Windows and Linux |

## Configuration and REST demo contract

The MCP endpoint is `http://127.0.0.1:8080/worker-coordinator/mcp`, with logical server name `worker-coordinator`, version `0.1.0`, and ASYNC Streamable HTTP. The preserved default catalog defines `allocate_worker_id`. The `payments` profile registers the payment tool alone, `workers` registers nine coordinator tools, and `worker-payments` registers all ten; default MCP remains disabled.

The original demo assumption was `POST http://127.0.0.1:9090/workers/ids`, request `{}`, response `{"workerId":"worker-1001"}`. The inspected real coordinator does not expose that endpoint. The user selected the separate worker sample's `POST /api/v1/payments` instead. The gateway returns backend-issued numeric payment IDs exactly as strings; it generates only per-invocation request keys for idempotent upstream retries. See [the actual contract](docs/REAL_COORDINATOR_CONTRACT.md) and [ADR 0003](docs/decisions/0003-local-payment-mcp-demo.md).

Default-profile deployment variables:

```powershell
$env:WORKER_COORDINATOR_BASE_URL = 'http://127.0.0.1:9090'
$env:GATEWAY_CATALOG_LOCATION = 'file:./examples/worker-catalog.json'
# Supply WORKER_COORDINATOR_BEARER_TOKEN through your private environment if needed.
.\mvnw.cmd spring-boot:run
```

These properties are now bound and validated. `.env.example` is reference text with no real credentials; Boot does not automatically load `.env`. Use shell/IDE environment settings or Spring configuration. The payments profile instead reads `PAYMENT_API_BASE_URL` (default `http://127.0.0.1:9091`) for its backend origin. Docker Compose explicitly loads its separate ignored env file.

Public metadata, method/path/body/JSON Pointer and version-3 argument mappings belong to the catalog. Backend origin, token and budgets belong to deployment properties. Defaults are connection timeout 2s, total upstream deadline 5s, response limit 64 KiB and MCP budget 10s. User-authorized automatic retries use exponential 100ms-to-1s backoff within that total deadline; redirects and hidden connector retries remain disabled. See [ADR 0002](docs/decisions/0002-user-authorized-allocation-retries.md) for this explicit change to the original no-retry policy.

## Build baseline and evidence

Pinned baseline: Java 21, Spring Boot 4.0.8, Spring AI BOM 2.0.1, Maven 3.9.11, Wrapper 3.3.4. The WebFlux MCP server starter is BOM-managed; the build adds no model starter or independently pinned MCP SDK. Surefire runs `*Test`; Failsafe runs `*IT` during `verify`.

`ScaffoldStartupTest` guards the default disabled endpoint; adapter tests use an independent JDK HTTP fixture. Final automated tests remain paused. The separate live SDK smoke negotiated `2025-11-25`, discovered one payment tool, created two distinct persisted payments and confirmed matching text/structured output. Current Wrapper `verify -DskipTests` compiled all tests and packaged the gateway; combined-profile SDK discovery listed ten tools. Test execution is still deferred. [WORKLOG.md](WORKLOG.md) records exact results and limitations; [ACCEPTANCE.md](docs/ACCEPTANCE.md) tracks missing evidence. No hosted CI run was performed.

## References

- [Spring AI compatibility and BOM](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Spring AI Streamable HTTP server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html)
- [Codex repository instructions](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Codex skills](https://learn.chatgpt.com/docs/build-skills)
- [Claude Code project memory](https://code.claude.com/docs/en/memory)
- [Claude Code skills](https://code.claude.com/docs/en/skills)

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development and verification workflow. Multiple virtual servers, native MCP proxying, hot reload, remote IAM, HA and a control plane remain later milestones.
