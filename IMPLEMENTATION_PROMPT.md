# Codex implementation prompt: milestone 1 MCP Gateway

Paste the text below into Codex in the repository where the gateway should be built. Keep `FUNCTIONAL_SPEC.md`, `DESIGN.md` and `examples/worker-catalog.json` available alongside this prompt. The prompt also states the essential requirements if used independently.

---

You are implementing the first milestone of my MCP Gateway project. Build the application and verify it; do not stop at a plan or generate only pseudocode. I want a small, readable implementation that helps me learn each step.

## Context and nonnegotiable boundary

I already have a Worker Coordinator service. It exposes REST and allocates a unique worker ID on each successful call. **Do not make that service an MCP server, modify its source, or require it to use Spring AI.**

Create a separate Spring Boot gateway that exposes one virtual MCP server. The gateway must accept a real MCP tool call, translate it into a REST request, invoke the Worker Coordinator, and convert its response into a real MCP tool result. “Virtual” means that the MCP definition and execution handler live in the gateway's JVM; there is no new MCP process or container per tool/service.

Read the supplied documents. `FUNCTIONAL_SPEC.md` is authoritative for behavior, `DESIGN.md` gives implementation guidance, and the example catalog defines the proposed file format. Follow the repository's applicable instructions and preserve unrelated work. If the repository is empty, create a single Maven module at its root; if it contains other applications, use a clearly named `mcp-gateway` module/directory.

## 1. Verify and scaffold the stack

1. Inspect the repository, its Java/build constraints and available toolchain before editing.
2. Prefer Java 21, Spring AI 2.0.1 and a compatible stable Spring Boot 4.0.x or 4.1.x patch. This baseline was checked against official docs on 7 October 2026. Resolve and pin an actual Boot patch version; do not leave a wildcard or a placeholder. Use the Spring AI BOM to manage Spring AI / MCP dependencies.
3. Use Maven, Maven Wrapper, Boot's executable packaging and `org.springframework.ai:spring-ai-starter-mcp-server-webflux` with Streamable HTTP and asynchronous handlers. Use `WebClient` for the upstream REST request.
4. Check the resolved version's actual SDK types, registration APIs, starter properties and test-client transport APIs before coding. Do not invent method names or copy incompatible constructors from a different release.
5. If the repository requires another Boot major version, verify an officially compatible stable Spring AI version first and document the change. Do not silently mix dependency generations or independently bump the BOM-managed MCP SDK.
6. Add no model-provider starter, model API key, Alibaba starter, Nacos, Spring Cloud Gateway, database or distributed control plane.

Official references:

- https://docs.spring.io/spring-ai/reference/getting-started.html
- https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
- https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html
- https://github.com/modelcontextprotocol/java-sdk
- https://modelcontextprotocol.io/specification/2025-06-18/server/tools
- https://modelcontextprotocol.io/specification/2025-06-18/basic/transports

Use current compatible APIs and the SDK's negotiated protocol version. Do not hand-code initialization, JSON-RPC envelopes, session management or SSE parsing. Do not disguise a normal REST controller as an MCP endpoint.

## 2. Implement the smallest useful runtime

Expose exactly one logical server, named `worker-coordinator`, at `http://127.0.0.1:8080/worker-coordinator/mcp`. Default application version: `0.1.0`. Default tool: `allocate_worker_id`.

Use `spring.ai.mcp.server.protocol=STREAMABLE`, ASYNC mode, and the version's supported endpoint property. Register file-defined SDK tool specifications programmatically. Disable annotation scanning and ToolCallback conversion if using low-level specifications so the same tool cannot register twice. Advertise tools only; disable unused resources, prompts, completions and live catalog-change capabilities.

Suggested responsibilities:

- Boot entry point and typed deployment properties.
- Immutable server, tool and REST-binding records.
- A file catalog loader and supported-format validator.
- A programmatic MCP registration configuration and dispatcher.
- A REST binding executor that knows HTTP/JSON, not MCP serialization.
- An SDK result mapper for success and sanitized execution errors.
- A focused WebFlux filter for the local Origin policy.

Combine classes where that makes the code clearer. Keep constructor injection and a single module. Do not create speculative executor factories or a plugin framework for one binding type.

## 3. Make the definition file drive registration and execution

Copy `examples/worker-catalog.json` to a classpath catalog. If the example is absent, create the same format:

- Root: `schemaVersion: 1`, `servers` array.
- One server: `id: worker-coordinator`, `tools` array.
- One tool: name, description, inputSchema, outputSchema, annotations, private binding.
- Input schema: an object with no properties and `additionalProperties: false`.
- Output schema: object with exactly required, nonempty string `workerId` and no other properties.
- Default hints: readOnly false, destructive false, idempotent false, openWorld true. This is a state-changing allocation; do not advertise it as read-only or idempotent.
- Default binding: type REST, backendRef worker-coordinator, method POST, path `/workers/ids`, requestBody `{}`, responseIdPointer `/workerId`.

This is our own gateway catalog format, not an existing Spring AI standard. Load and validate it once at startup into an immutable registry. Allow a configurable resource location through `GATEWAY_CATALOG_LOCATION`, including an external `file:` location. Catalog edits require restart.

Validate schema version, cardinality, fields, metadata, duplicate names/JSON keys, supported schema shapes, binding type, method, backend reference, path and JSON Pointer. Reject malformed, unsupported or ambiguous definitions before successful startup. Milestone 1 supports one server and one tool, POST/GET and the stated input/output schemas only. A second server/tool, native MCP binding or arbitrary new schema must fail clearly rather than being ignored.

Tool name, description and binding values must come from the catalog. Renaming the tool, changing its description or changing the supported REST binding in a new application context must work without editing Java. `tools/list` exposes public MCP metadata only; never expose binding details, backend addresses, tokens or catalog filenames.

Validate that the sole catalog server, configured server name and fixed endpoint agree. Do not assume the starter creates a new transport instance for each future catalog entry.

## 4. Implement the REST adaptation precisely

The actual coordinator contract has not been supplied. Document these **demo assumptions**, with a separate REST mock implementing them:

`POST http://127.0.0.1:9090/workers/ids`, JSON body `{}`, response `{"workerId":"worker-1001"}`.

The base URL is configured using `WORKER_COORDINATOR_BASE_URL`. An optional bearer token comes from `WORKER_COORDINATOR_BEARER_TOKEN`. Method/path/body/response selector are editable in the catalog. Support GET only when the body is absent. Do not guess alternate endpoints or add automatic fallback requests. If the real service later requires custom headers, query mapping or business arguments, document the required contract extension.

The base URL is a configured HTTP(S) origin. Reject userinfo, query and fragments. Binding paths start with `/` and cannot change origin, inject query/fragments or traverse paths. Client arguments cannot choose URLs, credentials or HTTP headers. Send exactly the configured static POST JSON body; GET has no body.

Use configurable connection timeout 2s, total upstream deadline 5s and response-body limit 64 KiB by default, with an enclosing MCP request budget of 10s. Verify property/connector settings for the resolved versions. Avoid blocking or `.block()` in request handlers.

Disable HTTP connector retries, application retries, redirect following and allocation-result caching. There is at most one application-level upstream attempt per accepted invocation, and exactly one on normal success. Do not generate an ID inside the gateway. Startup, health checks and discovery must make no allocation calls. A backend outage must not prevent catalog-valid startup or discovery.

Accept only a valid 2xx JSON response with an ID selected by the configured JSON Pointer. Permit a nonblank string or an integral JSON number. Preserve strings exactly; normalize an integer to exact base-10 text using integral/BigInteger parsing. No floating-point conversions. Reject missing/null/blank IDs, fractional numbers, arrays, objects, booleans, malformed/empty/oversized bodies and unsuccessful statuses.

On success, construct an SDK tool result with `isError: false`, `structuredContent: {"workerId":"..."}`, and a text block containing the same JSON. Project only this ID field, not the whole backend response. Do not fabricate a different ID if the coordinator returns a duplicate.

## 5. Implement errors and local protection

Let the SDK handle protocol/lifecycle failures and unknown tool behavior. The tool accepts `{}` or absent arguments normalized to `{}` if supported. Reject nonobject arguments and additional properties before any upstream request.

Gateway execution errors use `isError: true` and text JSON with `code`, sanitized `message` and `allocationOutcome`. Omit structuredContent on errors because the advertised output schema describes success. Application codes:

- `INVALID_ARGUMENTS`: no upstream call; allocationOutcome `not_attempted`.
- `UPSTREAM_UNAVAILABLE`: connection failure; outcome `unknown`.
- `UPSTREAM_TIMEOUT`: deadline exceeded; outcome `unknown`.
- `UPSTREAM_HTTP_ERROR`: non-2xx including redirects; outcome `unknown`.
- `UPSTREAM_INVALID_RESPONSE`: unusable response; outcome `unknown`.

An upstream timeout can happen after an allocation. Say that the allocation outcome is unknown and do not recommend automatic retry. Cancellation can cancel local work, but cannot promise coordinator rollback. This gateway does not provide exactly-once semantics.

Bind locally to `127.0.0.1`. Reject unapproved Origin headers on the MCP endpoint before execution; support configurable permitted localhost origins and nonbrowser clients without Origin. Do not treat CORS headers as enforcement. Remote authentication/TLS/authorization is a later milestone.

Log one safe terminal outcome per invocation with correlation ID, tool, duration and error category. Do not log tokens, raw upstream bodies, stack traces in client responses or IDs by default. Sanitize messages rather than returning exception text.

## 6. Build and prove the behavior

Work incrementally, completing each stage without pausing for routine confirmation:

1. Boot skeleton, Wrapper and resolved dependency set.
2. REST executor with an independent controllable HTTP mock.
3. Catalog validation and programmatic registration.
4. Real SDK-client-to-gateway-to-REST integration.
5. Documentation, external configuration and a smoke client.

Implement the acceptance table in `FUNCTIONAL_SPEC.md`. If used without that document, the essential tests are:

- SDK connection/negotiation and exact single-tool discovery from the static file.
- Startup and discovery cause zero coordinator allocation calls.
- Two calls cause two REST requests and return exactly the mock-issued IDs; verify method, path, body and optional bearer header.
- Text JSON and structuredContent agree and conform to the supported output schema.
- Unknown tool and invalid arguments do not invoke REST.
- Backend HTTP errors, disconnects, deadlines, invalid and oversized bodies produce sanitized errors with no retry or followed redirect.
- A canary secret in an upstream error does not appear in responses or logs.
- Nested JSON Pointer extraction, exact conversion of an integer larger than 2^53, and rejection of invalid ID types.
- Duplicate coordinator IDs are returned unchanged.
- New startup catalog changes tool name/description/binding; a running file edit does not reload.
- Missing/invalid files, duplicates, unsupported schemas, extra servers/tools and native MCP bindings fail startup.
- Allowed/disallowed Origin behavior and ordinary SDK client behavior without Origin.

Use a running Boot server, random test port and an actual compatible SDK client for protocol tests. Direct dispatcher calls alone are insufficient. Tests must never target the real coordinator or allocate real IDs. Use bounded waits and request counters rather than long arbitrary sleeps.

Supply a separate REST mock for local demonstration; only that fixture may generate demo IDs. Supply a runnable SDK smoke client that lists tools by default and invokes allocation only with an explicit `--allocate` option. Explain that the allocation option consumes an ID when configured for a real coordinator.

Run `./mvnw verify` and the appropriate local smoke flow. Report actual results. If tooling/network access prevents verification, leave the complete implementation and exact commands, but clearly state what was not run. Never invent successful build or protocol-test results. A mock-tested integration is not a verified live-coordinator integration.

## 7. Deliverables and completion report

Produce executable code, pinned build, Wrapper, static catalog, external config example, meaningful tests, REST mock, SDK smoke client and a README. The README must explain component boundaries, how the file materializes a virtual tool, how to run each process, how to configure the real REST contract, transport/client lifecycle, error behavior and test evidence.

Keep native MCP proxying, multiple virtual endpoints, hot reload, discovery/control-plane integration, generic REST mappings, enterprise IAM and HA outside this milestone. Mention their next steps briefly without claiming they exist.

At completion, summarize what was implemented, exact dependency versions, build/test results, demo assumptions and the commands to run the gateway and smoke client. Include a short walkthrough of discovery and one invocation so I can understand the implementation. Continue until milestone 1 is implemented and verifiable within the available environment.
