# Functional specification: virtual MCP Worker Coordinator gateway

Version: 1.0. Date: 7 October 2026. Status: implementation handoff.

## 1. Goal and ownership

Build an executable Spring Boot application that exposes one logical MCP server at `/worker-coordinator/mcp`. That server offers one tool, `allocate_worker_id`. Calling the tool must invoke the existing Worker Coordinator's REST API and return its allocated ID through MCP.

The gateway owns protocol adaptation, static metadata, REST execution, validation and error translation. The coordinator owns allocation, persistence and uniqueness. An MCP server in the gateway is a virtual interface backed by an ordinary REST service. No MCP library, endpoint, annotation, process or adapter is installed in the coordinator.

The first milestone must work without a model provider or model API key. A standards-compliant MCP client can discover and invoke the tool directly.

## 2. Scope

| Required in milestone 1 | Deferred |
| --- | --- |
| Basic Boot application and Maven Wrapper | Alibaba gateway dependencies and Nacos |
| Spring AI MCP server transport | Spring Cloud Gateway routing |
| One virtual endpoint and one allocation tool | Multiple endpoint/server isolation |
| Static catalog loaded at startup | Hot reload and a remote control plane |
| Translation into one REST request | Native MCP upstream proxying |
| Configurable backend URL and editable static REST binding | OpenAPI import and general mapping expressions |
| Bounded requests, sanitized errors and useful logs | Distributed session storage and HA deployment |
| Local REST mock, MCP smoke client and integration tests | OAuth, enterprise authorization and production rate limits |

Reject unsupported definitions at startup rather than ignoring them. In particular, a native MCP binding or a second server must produce a clear unsupported-configuration error in this milestone.

## 3. External REST contract

The real coordinator contract is not known yet. Use this default **only as a demo assumption**:

| Item | Demo value | How to replace it |
| --- | --- | --- |
| Base URL | `http://127.0.0.1:9090` | `WORKER_COORDINATOR_BASE_URL` |
| HTTP method | `POST` | Catalog `binding.method` |
| Path | `/workers/ids` | Catalog `binding.path` |
| Request | JSON `{}` | Catalog `binding.requestBody` |
| Successful status | Any `2xx` with a valid JSON body | Initial policy; tighten when the actual contract is known |
| Example response | `{"workerId":"worker-1001"}` | Backend-owned response |
| ID selector | JSON Pointer `/workerId` | Catalog `binding.responseIdPointer` |

Support `POST` and `GET` only initially. A `GET` binding must omit `requestBody`; reject a configured GET body. A POST body is static JSON and is sent exactly as configured. Do not forward MCP arguments into arbitrary URLs, headers or bodies.

An optional bearer token may be supplied through `WORKER_COORDINATOR_BEARER_TOKEN`; do not put it in the catalog, tool description or logs. Additional custom headers, query mapping and business arguments require a later contract change. The initial implementation must identify this limitation if the actual coordinator needs them.

Backend redirects must not be followed automatically. Successful status alone is insufficient: empty bodies, malformed JSON, absent IDs and invalid ID types are failures. Do not add a compatibility fallback that guesses a different path, method or ID field.

## 4. Static catalog

`examples/worker-catalog.json` defines the proposed catalog format. Required behavior:

1. Read a classpath default, or an operator-specified Spring resource location such as `file:./config/worker-catalog.json`.
2. Parse once at startup into an immutable model; do not read the file per request.
3. Require `schemaVersion: 1`, exactly one server with ID `worker-coordinator`, and exactly one tool. The tool's name and description come from the file; `allocate_worker_id` is the default name.
4. Support the declared empty-object input schema and the declared single-string `workerId` output schema. This milestone is not a general JSON Schema execution engine. Reject incompatible schema shapes rather than advertising schema that execution cannot honor.
5. Validate tool names, nonblank descriptions, annotations, required binding fields, backend references, HTTP method and JSON Pointer syntax. Reject duplicate names and unexpected catalog fields, including malformed JSON with duplicate object keys.
6. Allow metadata edits, a tool rename, a different REST path/method/body and a different response pointer without changing Java code, subject to those restrictions.
7. File changes take effect only after restart. Do not advertise tool-list change notifications for live updates.
8. Configuration errors prevent successful startup. They must not create a partly configured listener serving an empty or misleading tool list.

The catalog includes private execution bindings, but `tools/list` must expose only MCP tool metadata. It must not disclose backend addresses, bindings, authorization headers or local filenames.

## 5. MCP interface

Expose an actual MCP Streamable HTTP endpoint at `http://127.0.0.1:8080/worker-coordinator/mcp`. The Spring AI / MCP SDK implementation must handle protocol negotiation, lifecycle, request IDs, transport headers, session behavior and JSON-RPC serialization for its supported protocol versions.

Do not implement a REST controller that merely resembles MCP. Do not assume a bare HTTP POST to `tools/call` works without the client's required lifecycle. SDK client examples must initialize/establish the connection as required by the negotiated version.

The endpoint supports tool discovery and invocation. Resources, prompts, completions and model sampling are not part of this milestone; disable unsupported capabilities. The server name is `worker-coordinator`, with application version `0.1.0`.

### Tool input

The default tool has no business arguments. Its input is `{}`. An absent arguments field may normalize to `{}` when accepted by the SDK. A nonobject value is invalid. Any property, including a proposed upstream URL or ID, is invalid and must never reach the coordinator.

### Successful tool output

Return an SDK-created tool result with `isError: false`, `structuredContent: {"workerId":"<allocated ID>"}`, and one text content block containing the serialized same JSON object. This is the application contract; the SDK wraps the result for the transport.

Extract the ID using the configured JSON Pointer. Accept a nonblank JSON string or an integral JSON number. Preserve strings exactly; convert integers to exact base-10 strings without a floating-point conversion. Reject null, missing fields, empty/whitespace-only strings, booleans, fractional numbers, arrays and objects. Validate the normalized output against the supported output schema. Do not trim, prefix, regenerate or otherwise change a valid string ID.

Only project the ID into the result. Do not pass the coordinator's whole response to the client. A repeated ID from the coordinator remains a repeated ID in the gateway response; the gateway does not repair or certify uniqueness.

### Errors

| Condition | Client behavior | Upstream attempt |
| --- | --- | --- |
| Invalid JSON-RPC or transport/lifecycle violation | SDK protocol/transport error | None |
| Unknown tool | SDK-supported protocol error | None |
| Invalid business arguments detected by gateway | Tool result with `isError: true`, code `INVALID_ARGUMENTS` | None |
| Backend connection failure | Tool error `UPSTREAM_UNAVAILABLE` | No gateway retry |
| Upstream call deadline exceeded | Tool error `UPSTREAM_TIMEOUT` | No gateway retry |
| Non-2xx backend status, including 3xx | Tool error `UPSTREAM_HTTP_ERROR` | One attempt |
| Empty, malformed, oversized or invalid response | Tool error `UPSTREAM_INVALID_RESPONSE` | One attempt |

For tool errors, put a serialized object containing `code`, a sanitized `message` and `allocationOutcome` in a text content block, with `isError: true`. Do not include `structuredContent` on an error; the advertised output schema describes successful allocation only.

`allocationOutcome` is `not_attempted` for rejected arguments. It is conservatively `unknown` for upstream errors: the gateway usually cannot prove whether an allocation happened. Do not claim that a timeout rolled back an allocation. Do not instruct a client to retry automatically. Return no worker ID on an error.

Do not expose raw response bodies, credentials, stack traces, network URLs or implementation exception messages. Protocol errors remain SDK-owned; document the exact SDK behavior observed in tests rather than inventing an error code.

## 6. Reliability and local runtime

- Each accepted invocation performs at most one application-level REST request. On ordinary success, it performs exactly one. Disable connector retries, reactive retry operators, redirects and gateway result caching. This is not an exactly-once allocation guarantee; client retries or uncertain delivery can still allocate additional IDs.
- Suggested configurable defaults: connection timeout 2 seconds; total upstream deadline 5 seconds; response body limit 64 KiB; MCP request budget 10 seconds. The upstream budget must leave time for gateway translation and exceed neither the enclosing MCP budget nor its test/client budget.
- Use bounded connection/pool settings supplied by the chosen HTTP connector. Cancellation must cancel the local upstream subscription when possible; it cannot promise cancellation or rollback inside the coordinator.
- Startup does not allocate an ID. Health checks, catalog loading and `tools/list` must not invoke the allocation REST endpoint.
- Discovery remains available when the coordinator is unavailable. A catalog-valid gateway can start without a reachable backend; invocation then fails safely. Liveness concerns the gateway, not coordinator allocation.
- Bind the demo gateway to `127.0.0.1` by default. Reject disallowed browser Origin headers on the MCP endpoint; explicitly allow configured localhost origins for local tooling if needed. Absence of Origin must remain compatible with nonbrowser MCP clients. Do not mistake CORS response headers for Origin enforcement.
- The first milestone is for local development. Remote deployment needs its own authentication, authorization, TLS and capacity design. Do not quietly turn the local demo into a publicly exposed service.
- Log one terminal outcome per invocation with correlation ID, tool name, elapsed time and safe outcome/error category. Do not log credentials, full bodies or allocation IDs by default. Logs do not require an additional model call.

## 7. Acceptance criteria

Automated tests must exercise the actual running Boot application through a compatible MCP SDK client and a separately controlled HTTP mock. Unit tests alone do not establish MCP interoperability.

| ID | Required evidence |
| --- | --- |
| AC-01 | Maven Wrapper builds the executable artifact and starts Boot without any model credentials. |
| AC-02 | An SDK client connects to the configured endpoint, negotiates a supported protocol and lists exactly one default tool with the catalog's schemas and hints. |
| AC-03 | Discovery and startup cause zero allocation requests. Private binding metadata is absent from discovery. |
| AC-04 | Two successful calls result in two HTTP requests; results equal the corresponding mock-issued IDs. Text JSON equals structured output. |
| AC-05 | Request verification checks HTTP method, path, JSON body and configured authorization behavior. GET variant emits no body. |
| AC-06 | Unknown tool, nonobject arguments and unexpected argument properties cause zero allocation requests and an SDK/gateway error as specified. |
| AC-07 | Mock 4xx/5xx/3xx replies produce sanitized tool errors; redirects are not followed. Response bodies containing a canary secret are not returned or logged. |
| AC-08 | Delayed response and connection failure are bounded; request counters show no automatic retry. Timeout reports allocation outcome as unknown. |
| AC-09 | Null, absent, blank, malformed, oversized and wrong-type responses fail. A nested JSON Pointer works. An integer larger than 2^53 survives exactly as a string. |
| AC-10 | Two calls for which the mock returns the same ID return that same ID, proving the gateway does not fabricate uniqueness. |
| AC-11 | A new catalog loaded in a fresh context changes the tool description/name and binding without a Java edit. Editing a running file causes no live change. |
| AC-12 | Invalid catalog versions, missing files, bad references, duplicate definitions, unsupported schemas, multiple servers/tools and native MCP bindings prevent startup. |
| AC-13 | Disallowed Origin is rejected before a REST call. Allowed local Origin and a nonbrowser client without Origin behave correctly. |

A real-coordinator verification is optional until its contract and address are available. Clearly distinguish passing tests against a REST mock from a verified live integration.

## 8. Required implementation deliverables

An executable Boot project; pinned dependency versions; Maven Wrapper; application configuration; default and external catalog examples; focused automated tests; an independent REST mock for local demonstration; a runnable MCP SDK smoke client; and a README covering setup, real-service configuration, transport/client usage, test evidence and the next milestone.

The mock may generate demo IDs. Production gateway code must not contain that allocation behavior. No production Worker Coordinator source change is required or authorized by this specification.
