# Handover prompt: multiple virtual MCP servers

Copy the prompt below into the next coding session in this repository.

```text
Work in D:\ai-projects\mcp-gateway-server-java.

Implement the proposed multiple-virtual-server design: group tools by logical
server, expose separate MCP endpoints, and isolate catalogs and sessions while
sharing one Spring Boot application process. This request authorizes that scope
extension beyond the original single-server milestone. Do not expand into other
unfinished milestone work.

Read first:
- AGENTS.md; WORKLOG.md current state and latest entries;
  docs/IMPLEMENTATION_PLAN.md.
- docs/MULTIPLE_VIRTUAL_SERVERS.md and
  docs/decisions/0005-multiple-virtual-servers.md (proposed, unimplemented).
- IMPLEMENTATION_PROMPT.md, FUNCTIONAL_SPEC.md, DESIGN.md,
  docs/ACCEPTANCE.md and docs/decisions/0002 through 0004.
- docs/WORKER_TOOLS.md, current catalogs, production sources and tests.
Inspect git status and actual files; preserve unrelated and untracked work.

Current state and evidence:
- Java 21, Boot 4.0.8, Spring AI BOM 2.0.1, BOM-managed MCP SDK 2.0.0,
  Maven Wrapper 3.9.11; package dev.mcp.gateway, one Maven module.
- Default MCP is disabled. Profiles payments, workers and worker-payments
  expose one payment tool, nine coordinator tools, or all ten respectively.
- The local combined gateway was left running on
  http://127.0.0.1:8080/worker-coordinator/mcp. PID references are in ignored
  target/payment-gateway.pid and target/worker-tools-gateway.pid; verify actual
  process identity and current state before relying on them.
- Separate Docker coordinator REST is on 9090, payment REST on 9091, with
  private PostgreSQL. Coordinator source in
  D:\java-projects\distributed-coordinator must remain unchanged.
- Wrapper verify -DskipTests passed, including test compilation. Actual SDK
  discovery listed ten tools and negotiated 2025-11-25. Read-only get_product
  succeeded for product mcp-demo. The payment sample registered its namespace
  at startup; this agent did not invoke registration MCP tools. Worker lease
  mutations remain unverified. Earlier payment creations are historical evidence.
- Multiple virtual servers are currently documentation only. Full automated
  test execution remains paused by the user.

Required behavior:
1. In a new opt-in multi-server mode, host worker-coordinator with nine worker/
   namespace tools at /worker-coordinator/mcp, and payments with
   create_sample_payment at /payments/mcp, on one loopback listener/JVM.
2. Preserve existing profiles and catalog versions 1-3. Add strict version 4
   grouping complete tool definitions under server entries. Derive endpoints
   from validated server IDs; reject duplicate IDs/routes, duplicate tool names
   within one server, unsupported definitions and invalid backend references.
   Allow the same tool name across different servers.
3. Build one McpAsyncServer and one WebFluxStreamableServerTransportProvider
   per server. Each owns its identity, catalog, tools and sessions. Register
   only its own tool specifications. Compose SDK RouterFunctions directly.
4. Disable the starter's aggregate single-server/transport auto-configuration
   in multi-server mode. Use a separate gateway setting for the custom manager.
   Explicitly configure JSON mapping, capabilities, budgets and lifecycle.
   Do not flatten catalogs into global tool-registration beans.
5. Extract shared invocation logic from McpToolRegistrationConfiguration.
   Reuse schema validation, REST executor, result mapping and private backend
   settings. Each handler captures its server and validated tool definition;
   never dispatch through a global tool-name lookup.
6. Scope allowed backend references, Origins, request budgets and finite
   session/invocation limits by server. Validate policy/catalog agreement.
   Apply Origin checks before execution on every endpoint and HTTP method.
   Keep addresses, tokens and private bindings out of public discovery.
7. Manage startup rollback and bounded graceful shutdown of all runtimes and
   streams before disposing shared HTTP resources. Avoid duplicate closure.
8. Make the smoke client endpoint-aware and support explicit named tool calls
   with arguments. Discovery remains the default and has no upstream effects.

Implementation constraints:
- Inspect the matching resolved SDK/starter/transport sources before using
  APIs. Current source artifacts are in ignored target, including
  mcp-core-sources.jar, spring-ai-mcp-common-sources.jar,
  spring-ai-mcp-webflux-sources.jar and
  mcp-spring-webflux-2.0.1-sources.jar. The inspected provider supports
  messageEndpoint(...) and getRouterFunction(). Do not guess signatures or
  upgrade dependencies/protocols without official evidence and a decision.
- SDK owns MCP negotiation, sessions, envelopes, unknown tools and streaming.
  Keep ASYNC handlers and WebClient; no blocking request-handler calls.
- Preserve exact integral IDs/epochs, advertised-field response projection,
  sanitized errors without structured error content, and safe terminal logs
  including serverId. Worker ownership UUIDs remain caller supplied.
- Preserve user-authorized bounded application retries (ADR 0002), reusing
  exact invocation values and payment request keys. Connector retries,
  redirects and allocation caching remain disabled. Failed upstream operations
  retain allocationOutcome: unknown; do not promise rollback.
- No file/directory deletion, clean, destructive Git commands, commits, pushes,
  deployment, coordinator source changes or additional agents. Do not restart
  the existing demo or make live registration/lease/payment mutations unless
  explicitly requested. If Windows locks its JAR, compile/test-compile or use
  a separate build output rather than stopping the demo.
- Keep secrets in ignored private configuration. Never print or copy them.

Verification while the test pause remains active:
- Add meaningful unit and real SDK protocol integration tests using independent
  random-port HTTP mocks. Compile them, but DO NOT execute automated tests until
  the user explicitly resumes them. Do not interpret this handover as lifting
  that pause. Use .\mvnw.cmd -B -ntp '-DskipTests' verify where packaging is
  possible, or compile/test-compile if the existing JAR is locked. Report limits.
- A separate bounded discovery-only smoke against a new server with unavailable
  or independent mock backends is allowed; do not invoke live mutations.
- Cover isolated server identities/discovery, cross-server unknown-tool rejection,
  same-named tool bindings, backend/token separation, session rejection across
  endpoints on POST/GET/DELETE, independent termination, invalid arguments,
  Origin enforcement, startup validation and lifecycle/resource limits.
- After the user resumes tests, run full .\mvnw.cmd -B -ntp verify. Compilation,
  discovery and historical smoke results are not executed-test evidence.

Continue autonomously through the implementation and documentation without
routine confirmation. Keep changes small and explain consequential choices.
Update the design/ADR status factually, migration instructions, plan and
acceptance tracker only with actual evidence; append a WORKLOG.md entry with
exact commands/results, unresolved issues and next action. At handoff, clearly
distinguish implemented behavior, compiled tests, executed checks and remaining
verification. Shared-process catalog/session isolation does not isolate JVM
crashes or heap exhaustion and is not authentication/authorization.
```
