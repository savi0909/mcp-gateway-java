# MCP Gateway Server

A local Java MCP gateway exposing the existing coordinator's worker lease and namespace APIs alongside its separate payment sample. The coordinator manages leases, the payment worker generates payment IDs, and the gateway adapts REST and MCP.

**Current state:** the MCP foundation is implemented and verified with independent mocks: **121 unit/startup tests and 20 real Boot/SDK integration tests pass**. Default MCP is disabled. `worker-payments` exposes nine coordinator tools plus payment; optional `secured` adds JWT caller authentication, read/write permissions, tenant admission and session ownership. The existing running demo is unchanged. The updated executable is under `target/foundation`.

See [Worker tools and definitions](docs/WORKER_TOOLS.md) for tools, schemas and run commands, and [the payment demo](docs/PAYMENT_DEMO.md) for Docker setup. The running MCP address is `http://127.0.0.1:8080/worker-coordinator/mcp`.

For manual testing, see [MCP Inspector commands](docs/MCP_INSPECTOR.md): the worker/payment gateway adapts MCP to REST on port 8080, while the native URL shortener tools are available through HAProxy at `http://127.0.0.1:8119/mcp`.

For the big picture and a detailed code walkthrough, read the
[end-to-end MCP tutorial](docs/MCP_END_TO_END_TUTORIAL.md). It follows startup,
discovery, REST mapping, caller security, retries and uncertain outcomes, with
mock exercises and debugger checkpoints.

The [enterprise MCP gateway vision](docs/ENTERPRISE_MCP_VISION.md) records the
long-term direction: an LLM-independent platform with federation, identity,
policy enforcement and resilient execution, plus a separate control plane.
Its architecture, priorities and enterprise phases are future planning context;
the current foundation's verified behavior remains described below.

**Selected next implementation:** [UC-01 admission](docs/UC01_ADMISSION_PLAN.md)
under [identity/authorization requirements v1.4](docs/requirements/Enterprise_MCP_Identity_Authorization_Requirements_v1.4.md).
The first slice combines delegated ordinary-read authorization, minimal catalog
eligibility, durable admission audit and revocation. Design/implementation remain
pending; [all 32 identity acceptance criteria](docs/IDENTITY_ACCEPTANCE.md) remain
required for the full first release, separately from foundation evidence.

Start with the [independent standalone mock demo](docs/MOCK_DEMO.md). For caller
security and the response-loss/cancellation demonstration, see
[the secured foundation guide](docs/SECURED_MCP.md) and
[threat model](docs/SECURITY_THREAT_MODEL.md). Tenant controls apply at gateway
admission; unchanged backend storage has no new tenant isolation. HAProxy failover
and interactive OAuth/host walkthroughs are not established by these tests.

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
Tests are resumed. Read the final foundation evidence and residual limitations;
implement only the next selected scope. Update the worklog and acceptance evidence.
```

For Claude Code you can use `/implement-stage 1B` or `/review-stage 1B`. In Codex, ask it to use the `implement-stage` or `review-stage` skill. [AI_WORKFLOW.md](docs/AI_WORKFLOW.md) contains prompts for staged development, full implementation, review and handoff. Keep one active writer in a checkout; hand off through the worklog before switching tools.

AI coding tools require their own login/subscription or credentials; that is separate from the gateway, which has no model dependency. No tool permissions, global configuration, external MCP connections, or automatic allocation hooks are installed by this scaffold.

## Project map

| Path | Purpose |
| --- | --- |
| [docs/FUNCTIONAL_SPEC.md](docs/FUNCTIONAL_SPEC.md) | Authoritative behavior and AC-01 through AC-13 |
| [docs/DESIGN.md](docs/DESIGN.md) | Component boundaries and architecture guidance |
| [docs/IMPLEMENTATION_PROMPT.md](docs/IMPLEMENTATION_PROMPT.md) | Original full milestone implementation request |
| [docs/ENTERPRISE_MCP_VISION.md](docs/ENTERPRISE_MCP_VISION.md) | Owner-selected enterprise architecture, priorities, roadmap and design process; future scope |
| [docs/UC01_ADMISSION_PLAN.md](docs/UC01_ADMISSION_PLAN.md) | Selected next slice, dependencies, ordered design/implementation work and verification plan |
| [docs/IDENTITY_ACCEPTANCE.md](docs/IDENTITY_ACCEPTANCE.md) | Separate pending IA-AC-01 through IA-AC-32 baseline tracker |
| [Identity/authorization v1.4](docs/requirements/Enterprise_MCP_Identity_Authorization_Requirements_v1.4.md) | Preserved owner-supplied enterprise behavioral requirements |
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
| `src/test/java/` | Independent HTTP/JWKS fixtures, startup/adapter/policy tests and real Boot/SDK integration tests |
| `src/main/resources/application-secured.yml` | Opt-in JWT caller security; requires issuer, audience/resource and private policy |
| `examples/tenant-policy.json` | Synthetic admission-policy example; no credentials |
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

Final command: `.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' verify`
passed with 121 Surefire and 20 Failsafe tests, zero failures/errors/skips, and an
executable JAR. The separate build directory avoids the existing Windows demo's
JAR lock. CI executes full tests on Windows/Linux; no hosted CI run is claimed.
Boot 4.0.8 manages Spring Security 7.0.7 and Nimbus 10.4; AI/SDK versions stay pinned.

Tests use real SDK clients/running random-port Boot servers with independent
REST/JWKS fixtures. They verify discovery without backend calls (including an
outage), exact mappings/IDs/epochs, errors, catalog lifecycle, Origin, scopes,
tenant/owner/session denial, concurrency, response-loss recovery and cancellation.
A separate-process mock smoke also proved zero allocations during discovery and
exactly two allocations after two explicit SDK calls. Earlier live local payment
success remains historical; these failure/security experiments used mocks.
[WORKLOG.md](WORKLOG.md) and [ACCEPTANCE.md](docs/ACCEPTANCE.md) contain exact evidence.

The file catalog becomes immutable SDK tool specifications at startup. The SDK
negotiates and dispatches a call; the gateway validates arguments and, in secured
mode, authorizes the current transport identity before resolving the private
REST binding. The bounded executor sends the mapped request, projects advertised
response fields and supplies matching text/structured success to the SDK.
Errors omit structured content and report conservative outcomes. Per-invocation
payment keys survive retries; a new invocation creates a new key. Physical HTTP
reset/local disposal can cancel work; graceful SDK closure alone does not prove it.

## References

- [Spring AI compatibility and BOM](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Spring AI Streamable HTTP server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html)
- [Codex repository instructions](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Codex skills](https://learn.chatgpt.com/docs/build-skills)
- [Claude Code project memory](https://code.claude.com/docs/en/memory)
- [Claude Code skills](https://code.claude.com/docs/en/skills)

See [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow. Multiple virtual servers,
native MCP proxying, hot reload, enterprise IAM, HA and a control plane remain
later scopes. The operations assistant belongs in a separate repository after
the remaining MCP learning gates are selected and evidenced.
