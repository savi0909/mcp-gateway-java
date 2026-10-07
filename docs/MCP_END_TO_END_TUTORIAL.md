# MCP gateway: an end-to-end architecture and code walkthrough

Study guide for the foundation in this repository, inspected on **2026-10-07**.
Read it alongside the source: the goal is to explain why each boundary exists,
then follow actual values through the methods that enforce it.

The foundation's recorded verification is **121 unit/startup tests + 20 running
Boot/SDK integration tests**, with zero failures, errors or skips. Those tests
ran during the foundation handover. Creating this tutorial did not rerun them.
Commands below are reproduction instructions, not additional execution claims.

## How to study this guide

For the big picture, read sections 1–3, 7, 12 and 17. For a code walkthrough,
read sequentially with the linked files open. For hands-on learning, use section
15's independent mock and section 16's debugger exercises. Each checkpoint asks
you to explain an invariant before moving on.

The evidence labels mean:

| Label | Meaning |
| --- | --- |
| **Implemented in supplied code** | Present in this checkout; follow the linked source. Tests are identified separately. |
| **Described but not implemented** | A proposal or future application boundary; do not assume it runs. |
| **Production enhancement** | A possible next design, not current behavior or proof of deployment. |
| **Recorded mock evidence** | An experiment that already ran against independent local fixtures. |
| **Historical live evidence** | An earlier local live check; it does not prove all failure paths. |

Navigate by topic:

1. [What the whole system does](#1-what-the-whole-system-does)
2. [Profiles, tools and ownership](#2-profiles-tools-and-ownership)
3. [The source map and startup](#3-the-source-map-and-startup)
4. [Configuration and the catalog](#4-configuration-and-the-catalog)
5. [Schema validation and REST binding](#5-schema-validation-and-rest-binding)
6. [The SDK lifecycle and registration](#6-the-sdk-lifecycle-and-registration)
7. [A complete read and write walkthrough](#7-a-complete-read-and-write-walkthrough)
8. [Reactive execution and resource limits](#8-reactive-execution-and-resource-limits)
9. [Retry, idempotency and response loss](#9-retry-idempotency-and-response-loss)
10. [Authentication and caller propagation](#10-authentication-and-caller-propagation)
11. [Permissions, tenant admission and sessions](#11-permissions-tenant-admission-and-sessions)
12. [Results, errors and cancellation](#12-results-errors-and-cancellation)
13. [Capacity and operational tradeoffs](#13-capacity-and-operational-tradeoffs)
14. [How the tests establish evidence](#14-how-the-tests-establish-evidence)
15. [Run an independent local demonstration](#15-run-an-independent-local-demonstration)
16. [Code reading, debugging and exercises](#16-code-reading-debugging-and-exercises)
17. [End-to-End Mental Model](#17-end-to-end-mental-model)
18. [End-to-end scenarios](#18-end-to-end-scenarios)
19. [Mechanism to question map](#19-mechanism-to-question-map)
20. [Key Concepts to Retain](#20-key-concepts-to-retain)
21. [Principal Engineer drill-down questions](#21-principal-engineer-drill-down-questions)
22. [References used](#22-references-used)

## 1. What the whole system does

**Implemented in supplied code.** This gateway gives an MCP client a standard
way to discover and invoke selected operations of existing REST services. It
does not implement worker leasing or payment persistence. It translates a
validated tool call into a bounded HTTP operation and translates the response
back into an SDK tool result.

```text
User / deterministic program
            |
            v
Host application                   Future operations assistant lives here
            |
            v
MCP client                         McpSmokeClient today
            |
            | Streamable HTTP: initialize, tools/list, tools/call
            v
Gateway MCP server                 This repository; SDK + Spring WebFlux
            |
            +-- REST --> Worker Coordinator: namespace and lease operations
            |
            +-- REST --> Payment API: sample creation and backend-issued ID
```

In MCP terminology, a **host** manages the user experience and client
connections; a **client** speaks the protocol to a server; a **server** exposes
capabilities. A host can connect to multiple servers. These roles do not require
a language model inside every process. See the [MCP architecture specification](https://modelcontextprotocol.io/specification/2025-11-25/architecture).

Here, `McpSmokeClient` is enough to demonstrate interoperability without an LLM.
Spring AI supplies MCP server integration; the application has no model starter,
model key, prompt loop or agent planner. An eventual assistant can consume these
tools from a separate repository and decide when to request them.

### Three connections that must stay distinct

```text
REST adaptation:
  client -- MCP --> Java gateway -- REST --> coordinator / payment API

Native MCP:
  client -- MCP --> shortener's own MCP server

HTTP routing:
  client -- MCP over HTTP --> HAProxy -- HTTP --> native shortener backend
```

**Historical live evidence.** Native shortener discovery through HAProxy at
`http://127.0.0.1:8119/mcp` worked. Its separate project records 49 passing tests.
Those are not this gateway's tests. HAProxy is not aggregating the gateway's tool
catalog, and this gateway is not proxying native shortener MCP messages.

**Described but not implemented.** Controlled HAProxy backend failure/recovery,
the interactive host/Inspector authorization walkthrough and the separate
operations assistant remain subsequent work. A successful discovery request
does not establish failover or exactly-once execution.

**Checkpoint:** Explain where a model would run, where a lease is allocated,
and where protocol negotiation runs. The invariant is that adding MCP to the
gateway leaves the existing coordinator a REST service. The common mistake is
equating an MCP server with an autonomous assistant.

## 2. Profiles, tools and ownership

**Implemented in supplied code.** The listener is loopback `127.0.0.1` and the
single logical server is `worker-coordinator`, with fixed endpoint
`/worker-coordinator/mcp`. Profiles choose the startup catalog.

| Profile | Public tools | Security |
| --- | --- | --- |
| Default | MCP disabled; endpoint is absent | No tool execution |
| `mock` | `allocate_worker_id` | Independent unauthenticated local demo |
| `payments` | `create_sample_payment` | Unauthenticated unless layered with `secured` |
| `workers` | Nine coordinator tools | Unauthenticated unless layered with `secured` |
| `worker-payments` | All ten coordinator/payment tools | Unauthenticated unless layered with `secured` |
| `workers,secured`, `payments,secured`, `worker-payments,secured` | Same selected public catalog | JWT, scopes, tenant admission and session ownership |

The secure allowlist covers the ten coordinator/payment names. Layering
`secured` onto `mock` fails because `allocate_worker_id` has no explicit secure
authorization rule. Legacy opt-in profiles are local demos, not implicitly
authenticated deployments.

The original `/workers/ids` allocation is a synthetic contract. Inspection found
that the real coordinator exposes namespace and lease APIs instead. The mock
preserves the original contract for learning; the payment sample supplies a
separate backend-generated ID demonstration. See [the actual coordinator contract](REAL_COORDINATOR_CONTRACT.md).

| Tool | REST binding | Owns the business operation |
| --- | --- | --- |
| `register_product` | POST `/api/v1/products` | Coordinator |
| `get_product` | GET `/api/v1/products/{productId}` | Coordinator |
| `register_service` | POST `/api/v1/products/{productId}/services` | Coordinator |
| `get_service` | GET `/api/v1/services/{serviceId}` | Coordinator |
| `register_worker_type` | POST `/api/v1/services/{serviceId}/worker-types` | Coordinator |
| `get_worker_type` | GET `/api/v1/worker-types/{workerTypeId}` | Coordinator |
| `acquire_worker` | POST `/api/v1/workers/acquire` | Coordinator |
| `renew_worker_lease` | POST `/api/v1/workers/renew` | Coordinator |
| `release_worker` | POST `/api/v1/workers/release` | Coordinator |
| `create_sample_payment` | POST `/api/v1/payments` | Payment API |

### Four identifiers with different meanings

| Value | Who supplies it? | Meaning |
| --- | --- | --- |
| `workerId` | Coordinator | Reusable worker slot; not a globally unique payment ID |
| `epoch` | Coordinator | Lease generation carried exactly through the gateway |
| `instanceId`, `registrationId` | Caller, checked against policy when secured | Owner identity used by coordinator lease operations |
| `clientIdempotencyKey` | Gateway, once per payment invocation | Reused for that invocation's REST attempts |
| `paymentId` | Payment backend | Persisted sample result; gateway returns it as a string |

A worker ID alone does not represent the complete lease. Retain the namespace,
owner, epoch and expiry, and use the documented coordinator contract. This
gateway does not implement downstream enforcement of fencing epochs.

**Checkpoint:** Can you explain why the gateway may generate a payment request
key but must not generate a payment result ID or worker owner UUID? Each value
has a different owner and lifetime. Confusing them changes the external contract.

## 3. The source map and startup

**Implemented in supplied code.** The code is deliberately small. Start with
[McpGatewayApplication](../src/main/java/dev/mcp/gateway/McpGatewayApplication.java),
the Boot entry point. The interesting behavior is in beans and immutable values,
not in `main`.

| File | Read it to understand |
| --- | --- |
| [GatewayProperties](../src/main/java/dev/mcp/gateway/config/GatewayProperties.java) | Private deployment origins, tokens and bounds |
| [FileCatalogLoader](../src/main/java/dev/mcp/gateway/catalog/FileCatalogLoader.java) | Strict startup validation and immutable tool definitions |
| [FlatObjectSchema](../src/main/java/dev/mcp/gateway/catalog/FlatObjectSchema.java) | Supported input validation and output projection |
| [RestBinding](../src/main/java/dev/mcp/gateway/rest/RestBinding.java) | Safe mapping from arguments to private path/body |
| [RestExecutorConfiguration](../src/main/java/dev/mcp/gateway/config/RestExecutorConfiguration.java) | Connection pool and budget checks |
| [RestBindingExecutor](../src/main/java/dev/mcp/gateway/rest/RestBindingExecutor.java) | HTTP, byte limits, retries, deadline and response parsing |
| [McpToolRegistrationConfiguration](../src/main/java/dev/mcp/gateway/mcp/McpToolRegistrationConfiguration.java) | SDK tools, invocation validation, authorization and result assembly |
| [McpOriginFilter](../src/main/java/dev/mcp/gateway/mcp/McpOriginFilter.java) | HTTP Origin rejection before execution |
| [SecurityConfiguration](../src/main/java/dev/mcp/gateway/security/SecurityConfiguration.java) | JWT validation, metadata and authenticated transport context |
| [SessionAdmissionFilter](../src/main/java/dev/mcp/gateway/security/SessionAdmissionFilter.java) | Current caller and session ownership |
| [TenantPolicy](../src/main/java/dev/mcp/gateway/security/TenantPolicy.java) | Trusted resource relationships and per-tool decisions |
| [Caller](../src/main/java/dev/mcp/gateway/security/Caller.java) | Immutable authenticated identity snapshot |
| [McpSmokeClient](../src/main/java/dev/mcp/gateway/demo/McpSmokeClient.java) | Actual SDK client, discovery and explicit calls |

### Startup is validation, not business execution

```text
Boot environment + active profiles
        |
        +--> GatewayProperties --> budget validation --> ConnectionProvider
        |                                               --> REST executor
        |
        +--> selected local catalog --> validated List<ToolDefinition>
        |                                  |
        |                                  +--> SDK AsyncToolSpecifications
        |
        +--> secure settings + policy, if enabled
                                           |
                                           +--> caller/session filters
                                           +--> secured transport context
        |
        v
Spring AI starter constructs SDK server and exposes transport routes
```

This diagram shows dependencies, not a promise about an arbitrary bean creation
sequence. In secured mode, policy construction also depends on the validated
tool list so unknown authorization names fail startup.

`McpToolRegistrationConfiguration` is conditional on
`spring.ai.mcp.server.enabled=true`. Its `toolDefinitions` bean verifies the
listener address and loads the catalog. `catalogTools` converts those definitions
to SDK specifications. The starter wires protocol and transport around them.
The secured transport bean supplies its own context extractor and session bounds.

Creating the REST executor does not open an allocation request. Loading the
catalog does not probe business backends. An unreachable backend can therefore
coexist with successful startup and discovery: service readiness for execution
is a different property from catalog availability.

The POM pins Java 21, Boot 4.0.8 and Spring AI BOM 2.0.1. The BOM resolves SDK
2.0.0. Boot manages Spring Security 7.0.7 and Nimbus 10.4. Jackson uses
`tools.jackson` here. Do not copy constructors or property names from a tutorial
for a different Spring/SDK generation. The [dependency baseline](DEPENDENCY_BASELINE.md)
records source inspection; its initial-stage remarks are historical. Current
verification is recorded in [WORKLOG](../WORKLOG.md).

**Checkpoint:** What happens if the catalog is invalid? Startup fails rather
than exposing partially understood tools. What happens if the backend is down?
Discovery can still work. Avoid treating the two failures as interchangeable.

## 4. Configuration and the catalog

**Implemented in supplied code.** Three separate inputs answer three separate
questions:

```text
Catalog                Deployment configuration       Tenant policy
What may be called?    Where/how is REST reached?       Who may call it on what?
names + schemas        origins + tokens + budgets       subjects + relationships
private bindings       Origin allowlist                scopes checked in code
```

A catalog contains both public metadata and private bindings. Only the public
part is copied into the SDK `Tool`. A client sees the name, description, schemas
and annotations. It does not receive backend origins, authorization tokens,
`backendRef`, `bodyArguments` or `pathArguments` through discovery.

Open [application.yml](../src/main/resources/application.yml) and the selected
profile file before reading a catalog. Defaults are:

| Setting | Default | Purpose |
| --- | --- | --- |
| `server.address` | `127.0.0.1` | Local listener boundary |
| `server.port` | `8080` | Local demo port, override for independent runs |
| MCP request timeout | `10s` | SDK request budget |
| `gateway.upstream.connect-timeout` | `2s` | Connection and pending acquisition bound |
| `gateway.upstream.deadline` | `5s` | Total executor time including attempts/backoff |
| `gateway.upstream.max-response-bytes` | `65536` | Successful upstream body byte limit |
| `gateway.allowed-origins` | localhost/127.0.0.1 on `6274` | Exact approved Inspector origins |

The `worker-payments` profile uses two backend references with separate origins
and optional REST tokens. The original payment-only version uses the legacy
`worker-coordinator` reference pointed at the payment API. Read the selected
profile rather than inferring a host from the reference's name.

`GatewayProperties` rejects unsupported backend keys, unsafe origins, invalid
Bearer syntax and invalid time/byte budgets. It requires the upstream deadline
to be strictly less than the MCP request budget. Backend origins cannot contain
credentials, arbitrary path prefixes, queries or fragments. `.env.example` is
reference text; Boot does not automatically load `.env`.

### Follow the catalog loader

Open `FileCatalogLoader.load` and `validate` and trace:

1. Accept only `classpath:` or `file:` catalog sources; cap the file at 1 MiB.
2. Parse with duplicate-field detection and reject trailing JSON.
3. Require supported integer `schemaVersion`, exactly one server and the expected
   server identity/endpoint configuration.
4. Accept one tool for legacy versions 1/2, or 1–32 tools for version 3.
5. Reject duplicate tool names, unknown fields and unsupported schema/binding forms.
6. Validate all path/body mappings and annotations.
7. Freeze nested maps/lists into immutable metadata and definitions.

Version 1 is the original worker-ID contract; version 2 is the empty-input
payment contract; version 3 supports the coordinator mappings and a payment tool.
This is an application catalog version, distinct from the negotiated MCP protocol
version and the SDK artifact version.

The catalog is not a general plugin language. Rejecting unsupported shapes keeps
the runtime contract auditable. The file is read at startup and is not reloaded
on each request. Editing it while the process runs does not alter existing SDK
tools. Restart establishes a new immutable snapshot.

**Checkpoint:** Explain why discovery can reveal a tool schema but cannot reveal
its private REST binding. The common mistake is serializing the entire internal
`ToolDefinition` as public discovery data.

## 5. Schema validation and REST binding

**Implemented in supplied code.** Open
[coordinator-payment-catalog.json](../examples/coordinator-payment-catalog.json)
and find `register_service`. Its public arguments include `productId`,
`serviceId` and `serviceName`; all are required and extra arguments are rejected.
Its private binding is:

```json
{
  "type": "REST",
  "backendRef": "worker-coordinator",
  "method": "POST",
  "path": "/api/v1/products/{productId}/services",
  "requestBody": {},
  "bodyArguments": {
    "serviceId": "serviceId",
    "serviceName": "serviceName"
  },
  "pathArguments": {
    "productId": "productId"
  }
}
```

Mapping keys are target fields/placeholders; mapping values are input argument
names. This example happens to use identical names. Do not rely on that as a
universal convention. Startup checks ensure that every input field is explicitly
mapped into the binding and that mappings do not overwrite static body fields.

```text
arguments                       resolved binding
productId = p       ----------> /api/v1/products/p/services
serviceId = s       ----------> {"serviceId":"s",
serviceName = Service -------->  "serviceName":"Service"}
```

### `FlatObjectSchema` validates a deliberate subset

This class supports the flat types and constraints the catalog needs, rather
than implementing arbitrary JSON Schema. Version-3 schemas require all declared
fields and `additionalProperties:false`. Supported fields include strings,
integers, numbers, booleans, canonical UUIDs, specified length/range constraints,
two allowed string patterns and the lease-duration string/number union.

`accepts(schema, arguments)` uses the same projection machinery with extra-field
rejection. A string `"16"` is not an integer `16`; an unknown field is not silently
dropped from input. Output projection allows additional upstream fields but
copies only the advertised fields and checks every required field's type.

Integer values remain exact through `BigInteger`; fractional values use
`BigDecimal`. String length is checked by Unicode code points. The UUID schema
check accepts canonical spelling case-insensitively, while secure owner matching
compares the supplied string to the provisioned UUID's `toString()` spelling.
Use the exact provisioned owner strings rather than assuming normalization.

### `RestBinding.resolve` turns a template into one invocation

For path parameters, placeholders must occupy complete path segments. Values
must match the restricted safe identifier pattern. The binding rejects traversal,
queries, fragments, authority changes, encoded slashes, dangerous decoding and
unsupported methods. POST body values are inserted as JSON values, not assembled
with string concatenation. GET has no request body.

`target(origin)` resolves the private path against the configured backend origin
and checks scheme/authority again. The client cannot supply a URL argument and
make this gateway fetch an arbitrary host.

After resolution, the returned binding has concrete path/body values. Retries
operate on this resolved invocation rather than recalculating mutable caller
values. `RestBinding`'s string representation redacts private details.

**Checkpoint:** Given `productId="../admin"`, identify the rejection before HTTP.
Given a missing `serviceName`, identify input validation failure. The invariant
is that unvalidated arguments never become a REST target or body.

## 6. The SDK lifecycle and registration

**Implemented in supplied code.** The SDK owns negotiation, protocol envelopes,
session lifecycle and unknown-tool dispatch. There is no controller pretending
to be MCP by manually parsing JSON-RPC.

```text
SDK client                         SDK server
   | ---- initialize ------------> |
   | <--- negotiated capabilities- |
   | ---- initialized notification>|
   | ---- tools/list ------------> | --> public startup catalog; zero REST calls
   | <--- tool definitions ------- |
   | ---- tools/call(name,args) --->| --> registered gateway handler --> REST
   | <--- CallToolResult ---------- |
```

Initialization establishes the protocol and capabilities before normal
operation. The SDK client handles the initialization notification and transport
headers. See the [MCP lifecycle specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle).
Recorded clients negotiated `2025-11-25`; application version `0.1.0`, catalog
version 3 and SDK version `2.0.0` are separate dimensions.

### Read `specification` before `invoke`

`McpToolRegistrationConfiguration.specification` creates an SDK `Tool` from only
public fields, then installs the application handler:

```java
return McpServerFeatures.AsyncToolSpecification.builder().tool(tool)
        .callHandler((exchange, request) -> invoke(definition, executor, request, exchange, policy)).build();
```

The lambda captures the validated definition, executor and optional policy.
`request` contains the caller's tool arguments; `exchange` supplies SDK context,
including the authenticated transport identity in secured mode. The private
binding remains inside that captured definition.

The customizer calls `server.validateToolInputs(false)`. This does **not** remove
gateway input validation. It avoids SDK-generated detailed schema diagnostics;
`invoke` applies the supported schema itself and returns a sanitized error.
Unknown tool names still belong to SDK dispatch.

Tool annotations are descriptive hints. A `readOnlyHint` is not permission, and
an `idempotentHint` does not enforce backend deduplication. Catalog validation
checks their supported form; authorization and retry behavior live in code.

### The client is a useful reading anchor

The real calls in `McpSmokeClient` use:

```java
var initialized = client.initialize().toFuture().get(15, TimeUnit.SECONDS);
var tools = client.listTools().toFuture().get(12, TimeUnit.SECONDS).tools();
```

Explicit allocation uses `client.callTool(new McpSchema.CallToolRequest(name,
Map.of(), null))`. The third argument is MCP metadata, not an authentication
identity. Discovery is the default; `--allocate` opts into backend mutation.
The client checks matching text/structured output and avoids printing IDs.

The command-line client waits on futures because it is a standalone program.
That is different from blocking a WebFlux request handler: handlers return
publishers and do not call `.block()`.

**Checkpoint:** Why can `tools/list` succeed while `tools/call` fails? Discovery
is startup metadata; execution depends on current credentials, policy and the
backend. A successful handshake is not a business execution test.

## 7. A complete read and write walkthrough

**Implemented in supplied code.** Use these as value traces. Their concrete
responses mirror independent fixtures, not claims about current live data.

### Read: Alice requests product `p`

Assume a secured `workers` or `worker-payments` profile, an initialized session,
and a valid Alice token with `tenant-a` and `gateway:read`. The example policy
authorizes product `p` for that tenant.

```text
SDK call get_product({productId:p}) + current Authorization header
  --> Origin filter
  --> JWT verification
  --> known caller + matching session owner
  --> SDK dispatch to get_product handler
  --> input schema accepts productId
  --> TenantPolicy.allows: read scope + owned p
  --> RestBinding.resolve: /api/v1/products/p
  --> GET to configured coordinator origin with its REST token
  --> parse bounded JSON --> project public fields
  --> SDK CallToolResult: matching text and structured content
```

Open `McpToolRegistrationConfiguration.invoke` and follow the branches:

1. `Mono.defer` creates per-subscription correlation, start time and terminal
   outcome. The default outcome is `cancelled` until a completed branch replaces it.
2. Null arguments become an empty map. The object-response coordinator binding
   selects `FlatObjectSchema.accepts` for strict input validation.
3. Invalid input returns `INVALID_ARGUMENTS`, `not_attempted`, with no executor call.
4. With a policy, `exchange.transportContext().get(Caller.ATTRIBUTE)` must contain
   the server-established caller. `policy.allows` must return true.
5. `definition.binding().resolve(arguments)` produces the concrete binding.
6. `executor.execute(...).map(...)` maps the HTTP-independent result into MCP.
7. `doFinally` emits a terminal category and duration without the arguments or ID.

Suppose the backend response is:

```json
{
  "productId": "p",
  "productName": "Product",
  "status": "ACTIVE",
  "privateBackendField": "internal",
  "unusedNullableField": null
}
```

`RestBindingExecutor.project` returns `ObjectSuccess`. The MCP layer then calls
`FlatObjectSchema.project(outputSchema, success.fields(), true)`. Only the three
advertised fields survive. A missing `status` or wrong field type fails output
validation rather than producing a superficially successful tool result.

```json
{"productId":"p","productName":"Product","status":"ACTIVE"}
```

That object becomes `structuredContent`; serializing the same object produces
the first text content block. JSON member order is not the invariant: equality
of parsed text and structured output is.

### Write: Alice registers service `s` under product `p`

Assume the token contains `gateway:write`. Input is:

```json
{"productId":"p","serviceId":"s","serviceName":"Service"}
```

The example trusted policy has `services: {"s":"p"}`. Authorization checks both
the owned IDs and their relationship. Provisioning `s` in admission policy is
different from registering `s` in the business backend: the policy preauthorizes
the operation; the coordinator performs it and decides its business result.

`RestBinding.resolve` uses `productId` only in the path and puts `serviceId` and
`serviceName` in the body:

```text
POST /api/v1/products/p/services
Content-Type: application/json
Authorization: Bearer <configured coordinator credential, if present>

{"serviceId":"s","serviceName":"Service"}
```

The caller JWT is not that REST credential. If Alice tries `serviceId="s2"`
under `productId="p"`, policy denies the mismatched parent because `s2` belongs
to `p2`. It returns `ACCESS_DENIED` before the REST request exists.

### Lease: acquire, then renew or release

`acquire_worker` requires product, service, worker type, region, `instanceId` and
`registrationId`. In the synthetic Alice policy, those are `p`, `s`, `w`, `0`
and the two provisioned owner UUID strings ending in `001` and `002`.

The coordinator's acquire response includes namespace, region, `workerId`,
`epoch`, `instanceId`, `leaseExpiry` and `leaseDuration`. Renew/release additionally
take the returned worker ID and epoch in their input. The catalog intentionally
does not advertise every possible backend field; release projects only
`released` and `epoch`.

The integration fixture uses epoch `9007199254740993`, greater than the exact
integer range of ordinary JavaScript floating-point numbers. Java-side parsing,
validation and projection preserve that integer. A future JavaScript consumer
must also choose a lossless strategy; server correctness cannot fix a client
that rounds parsed JSON numbers.

### Payment: empty public input, static private request

Input to `create_sample_payment` is `{}`. The combined catalog privately selects
payment-api, POST `/api/v1/payments`, static `{"amount":"12.34"}`, request-key
field `clientIdempotencyKey` and response pointer `/id`.

```text
{} public arguments
  --> static body + newly generated UUIDv7 request key
  --> payment API
  <-- {"id":9007199254740993}
  --> pointer /id --> exact decimal string
  <-- {"paymentId":"9007199254740993"}
```

The amount is a string in this catalog. Do not infer arbitrary user-selectable
amounts from the tool name. A new input contract would need validation, mappings,
policy review and appropriate evidence.

**Checkpoint:** Trace `productId` and `serviceName` from input to HTTP and back.
Then explain why payment input is empty while its REST body is not. The common
mistake is assuming all tools simply forward their argument maps unchanged.

## 8. Reactive execution and resource limits

**Implemented in supplied code.** Read the executor as a publisher pipeline,
not as a synchronous sequence that has already run.

```text
construct Mono                  subscribe
  no request                       |
                                   v
                           create invocation body once
                                   |
                           request attempt publisher
                                   |
                           response status/body/projection
                                   |
                          retry selected failures
                                   |
                         one total deadline around attempts
                                   |
                           AllocationResult value
```

### Why two `Mono.defer` boundaries matter

The outer method is exactly:

```java
public Mono<AllocationResult> execute(RestBinding binding) {
    // A new request key per invocation, generated outside the retried publisher.
    return Mono.defer(() -> executeWithBody(binding, bodyForInvocation(binding)));
}
```

It delays body/key creation until subscription. Merely constructing the executor
or calling `execute` to obtain a publisher produces no business HTTP request.
Each new subscription gets its invocation body. The inner `Mono.defer` in
`executeWithBody` creates each HTTP attempt, but captures that already-created
body. Retrying the inner publisher therefore does not regenerate the payment key.

`map` transforms an emitted result; `onErrorMap` classifies an exception;
`retryWhen` resubscribes selected failures; `timeout` terminates an over-budget
pipeline; `onErrorResume` converts terminal executor errors into safe failure
values; `doFinally` observes completion/error/cancellation. These operations do
not require a thread to block while the network is idle. For API semantics, see
the [Reactor Mono reference](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Mono.html).

Do not add an internal `.subscribe()` to fire-and-forget backend work. The SDK's
subscription needs to own the request lifetime, error result and cancellation.

### Bound every phase you actually wait for

The configured HTTP client disables hidden connector retries and redirects.
Application retries are explicit in the executor. Connect timeout is 2s; HTTP
response timeout uses the upstream deadline. A Reactor `timeout` outside the
retry chain gives one 5s bound for acquisition, connection, request, body chunks
and executor parsing across attempts and backoff.

The final advertised-schema projection happens in the MCP layer after the
executor result. Keep that distinction when interpreting the code comment about
projection: the executor's deadline is not a stopwatch around every possible
operation in the entire HTTP/security/SDK request.

### Read success and error bodies differently

For 2xx responses, `read` joins `DataBuffer` chunks with the byte limit. It copies
bytes, releases the joined buffer in `finally`, parses strict JSON and projects
the result. A zero-length success body is invalid; a successful status alone
does not satisfy the tool contract.

For non-2xx responses, the error category is already known from status. The code
subscribes and immediately cancels the body rather than waiting to drain an
unbounded or stalled error stream. `doOnDiscard` releases discarded buffers.
This detail prevents response cleanup from turning a known HTTP failure into a
long wait and avoids consuming private backend diagnostics.

Parsing rejects duplicate keys and trailing content. Malformed, oversized,
missing or inappropriate JSON yields a sanitized category. Parser exceptions
can contain source bytes, so the executor does not preserve them as causes in
its safe application failure objects.

**Checkpoint:** Where must a payment key be generated so retries reuse it?
Outside the retried publisher, inside the invocation subscription. The common
mistake is putting key creation inside the per-attempt `Mono.defer`.

## 9. Retry, idempotency and response loss

**Implemented in supplied code; recorded mock evidence.**
[ADR 0002](decisions/0002-user-authorized-allocation-retries.md) records the user's
explicit change from the original no-retry policy. The catalog's public hints do
not create this retry policy.

The retry configuration is:

```java
.retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(100))
        .maxBackoff(Duration.ofSeconds(1))
        .filter(error -> error instanceof AttemptFailure failure && failure.retryable))
.timeout(properties.upstream().deadline())
```

`Long.MAX_VALUE` does not mean an invocation runs forever. The outer deadline
ends it. Backoff grows from 100ms up to 1s, with Reactor's default jitter; timing
and the number of attempts vary. The policy provides a total time bound, not a
fixed attempt count.

| Observation | Retry? | Eventual category if it terminates there |
| --- | --- | --- |
| Connection/unavailable error | Yes, within total deadline | `UPSTREAM_UNAVAILABLE`, or deadline timeout |
| Attempt timeout | Yes, if total deadline leaves room | `UPSTREAM_TIMEOUT` |
| HTTP 408, 429 or 5xx | Yes | `UPSTREAM_HTTP_ERROR`, or deadline timeout |
| Other non-2xx, including redirects | No; never follow redirect | `UPSTREAM_HTTP_ERROR` |
| Invalid/oversized/missing successful JSON | No | `UPSTREAM_INVALID_RESPONSE` |
| Gateway input/policy denial | No execution | `INVALID_ARGUMENTS` / `ACCESS_DENIED` |

An attempt stalled for the full total deadline may leave no time for a retry.
There is no custom `Retry-After` implementation here, no circuit breaker and no
gateway allocation-result cache. Reusing arguments helps satisfy a backend's
idempotency contract, but it does not manufacture such a contract for arbitrary
coordinator or legacy operations.

### The hardest case: committed work, missing response

```text
Gateway                           Independent payment fixture
   | POST amount + key K ---------> |
   |                                | persist logical result R under K
   | <--- connection lost ----------X
   | wait backoff                   |
   | POST same amount + same K ----> |
   |                                | look up existing R
   | <--- original ID R ------------ |
```

`UuidV7RequestKey.next` creates the private request key. It uses time and random
bits with UUIDv7 version/variant bits. It does not provide a monotonic ordering
promise or generate the backend's result ID.

The fixture [CommittingPaymentMock](../src/test/java/dev/mcp/gateway/support/CommittingPaymentMock.java)
stores a logical result before dropping a response. In
`ResponseLossIT.retryRecoversOriginalCommittedResultButNewInvocationCreatesAnotherKey`:

| Stage | HTTP attempts | Logical commits | Key |
| --- | --- | --- | --- |
| Initialization/discovery | 0 | 0 | None |
| First request commits, response lost | 1 | 1 | K |
| Retry recovers original result | 2 total | 1 | Same K and exact request |
| Another MCP invocation succeeds | 3 total | 2 | A new key |

The useful guarantee is bounded **within-invocation** recovery, contingent on the
backend's deduplication behavior. It is not exactly-once execution across new
MCP calls. If every response is lost until the deadline, the result is unknown
even though the fixture retains one logical commit.

The native shortener has a different public contract: caller-supplied owner and
request key permit replay with the same exact arguments across invocations under
its own rules. Do not transfer that guarantee to this generated-key payment tool.
After a payment unknown outcome, a host must not automatically issue a fresh
tool invocation and assume it is the same payment.

**Production enhancement.** Durable replay across MCP invocations would require
a separately designed/versioned caller key contract and backend guarantees,
including handling the same key with different arguments. A gateway cache alone
would not safely replace durable backend deduplication.

**Checkpoint:** Can HTTP attempts exceed commits? Yes. Can an error imply no
commit? No. The invariant is exact invocation reuse for retries, paired with
conservative reporting when the backend result cannot be established.

## 10. Authentication and caller propagation

**Implemented in supplied code.** Read `SecuritySettings`, then
`SecurityConfiguration.securityChain`, `callerJwtDecoder` and `securedTransport`.
This is an OAuth resource server boundary. The gateway validates an access token;
an external authorization server issues it.

```text
Authorization server -- signs access token --> client
          |
          +-- publishes public JWKS --> gateway JWT decoder

client -- Authorization: Bearer <access token> --> gateway
gateway -- configured REST bearer, if any --> backend
```

The issuer's private signing key does not belong in the gateway. JWKS contains
public verification material. The local test issuer generates its signer in
memory; that fixture is not a production login service.

Spring Security provides reactive JWT decoding and verification integration;
this application layers explicit claim and deployment checks onto it. See
[Spring Security's JWT resource-server reference](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html).

### Authentication contract in this code

| Check | Why it matters |
| --- | --- |
| RS256 signature from configured JWKS | Only the configured issuer's accepted signing material verifies the token |
| Exact `iss` | A correctly signed token must come from the expected issuer contract |
| `aud` contains the configured MCP resource URI | A token for another resource is not accepted here |
| Header `typ` is `at+jwt` | Enforces this access-token format |
| Required `iat`, `exp`; `iat <= now`, `iat < exp`; valid `nbf` if present | Rejects future, expired or invalid time contracts |
| Nonblank `sub`, `tenant`; well-formed scope string | Establishes the input to trusted admission policy |

Timestamp validation uses zero clock skew in this bounded foundation.
`.validateType(false)` disables Nimbus's default type check so the custom
validator can require `at+jwt`; it does not disable signature verification.
Malformed token diagnostics are replaced with a safe `BadJwtException`.

The JWKS WebClient disables hidden retries and redirects, has 2s connect/response
bounds and a 64 KiB codec limit; decoding has a 3s outer timeout. The configured
issuer/JWKS/resource URLs require HTTPS unless the explicit loopback HTTP exception
is selected. Actual network authentication is separate from REST business traffic.

The configured resource URI, not an incoming Host or forwarded header, determines
the audience and metadata URL. MCP resource metadata advertises the issuer and
scopes; a 401 challenge directs a compatible host to it. These endpoints do not
implement login or another virtual MCP server. See the
[MCP authorization specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization).

### The filter order establishes trust

```text
Incoming HTTP request
  1. McpOriginFilter: highest precedence
  2. Spring Security: WebFilterChainProxy (-100)
  3. SessionAdmissionFilter (-90)
  4. SDK transport route and context extraction
  5. SDK dispatcher / tool handler
```

Origin is optional for non-browser callers. If present, exactly one approved
value is required. An unapproved Origin is rejected with HTTP 403 before
execution. A permissive CORS header would not implement this check, and Origin
is not a substitute for caller authentication.

Security's HTTP authentication context is not stored in a WebSession:
`NoOpServerSecurityContextRepository` is used. MCP sessions are a distinct SDK
mechanism. Every MCP HTTP request requires a currently valid token in secured
mode, including POST, GET and DELETE.

After JWT validation, `SessionAdmissionFilter` builds an immutable `Caller` from
issuer, subject, tenant, scopes and expiry and requires a known policy subject.
It adds `Caller.ATTRIBUTE` to server-side request attributes. The transport's
`contextExtractor` copies that caller into `McpTransportContext`; the handler
reads it from the SDK exchange.

```text
verified JWT --> Caller --> request attribute --> SDK transport context
                                                   |
                                                   v
                                            policy.allows(...)

tool arguments / caller `_meta` ----------------X-- cannot create Caller
```

This avoids global mutable caller state and assumptions about a thread-local
surviving reactive scheduling. Concurrent Alice/Bob calls have separate immutable
contexts. The handler checks expiry again at admission, but this is not continuous
revocation of a REST request already sent.

**Checkpoint:** Why is a tenant string in tool arguments not enough? The trusted
tenant must come from a verified identity. Why is a valid signature not enough?
Issuer, audience, claims and policy must also satisfy the resource contract.

## 11. Permissions, tenant admission and sessions

**Implemented in supplied code.** Authentication establishes the caller.
Authorization decides whether this caller may use this tool with these arguments.
Start with [tenant-policy.json](../examples/tenant-policy.json), which is synthetic
example data, then follow `TenantPolicy.knows` and `allows`.

### Resource relationships are trusted policy

```text
tenant-a
  product p --> service s --> worker type w
  product p2 -> service s2 -> worker type w2
  region 0
  alice --> exact instance UUID + registration UUID
  paymentAllowed = true

tenant-b
  product bp -> service bs -> worker type bw
  region 1
  bob --> different exact owner UUIDs
  paymentAllowed = false
```

The policy is loaded locally once, capped at 256 KiB, and validated into immutable
containers. Unsupported versions, ambiguous JSON, unknown fields, invalid parents
or overlapping IDs across tenants within a resource kind fail validation. Owner
instance/registration UUIDs cannot be shared between policy subjects.

The maximum is 32 tenants. This count is distinct from the catalog's 32-tool
limit and the SDK's secured 256-session limit.

`allows` evaluates:

1. Known subject/tenant and current unexpired caller.
2. Explicit tool allowlist and required scope: the three getters need
   `gateway:read`; registrations, lease operations and payment need `gateway:write`.
3. Payment admission flag for `create_sample_payment`.
4. Membership of each supplied product/service/type in the caller's tenant.
5. Parent agreement if both parent and child are supplied.
6. For leases, complete namespace, permitted region and exact caller-owner UUIDs.

Write scope does not imply read scope. Registration cannot introduce an unlisted
resource ID. A `get_service` call needs only service ID because policy already
knows its trusted tenant/parent; it does not first query REST to determine whether
the caller owns it.

An explicit tool-name allowlist means that adding a catalog tool to a secured
profile is not enough: startup requires an authorization policy for that name.
Do not widen that check to permit unknown operations without designing their
argument and ownership rules.

### The isolation boundary has a precise limit

These checks isolate **gateway admission**. They do not add tenant-aware storage
to the coordinator, prevent direct unauthorized backend access or establish a
persisted tenant owner for payments. Schema projection is not a response-owner
verification algorithm: this code does not cross-check returned business IDs
against the authorized input. It assumes the selected backend honors its REST
contract after gateway admission.

**Production enhancement.** Direct backend access control, persisted tenant
ownership and response-integrity rules would require the corresponding service
contracts and deployment design. They must not be inferred from a passing
cross-tenant gateway denial test.

### Session ownership is not token equality

`Caller.sameOwner` compares `(issuer, subject, tenant)`. Scopes and expiry belong
to the current token, not to permanent session identity. A refreshed token for
the same owner can use the session. A different subject or tenant cannot use a
stolen `Mcp-Session-Id` to become the owner.

```text
SDK creates session S
  --> response beforeCommit binds S to owner (issuer, alice, tenant-a)

later request S + current Alice token --> owner matches --> current policy checks
later request S + Bob token          --> HTTP 403, no handler/backend call
later request S + expired token      --> HTTP 401, no admission/backend call
```

The admission registry is in-memory and synchronized around small map operations,
not around network I/O. It caps entries at 256 and expires idle entries after
15 minutes during registry access. The secured SDK transport is also configured
for 256 sessions and 15-minute idle expiry. Successful DELETE removes the admission
entry. A failed admission bind returns 503 and suppresses the new session header.

Registry and SDK session lifecycles are separate; do not infer a distributed or
atomically shared session store from their matching limits. Restart loses local
sessions. The current design has no automatic policy reload or revocation service.

A subtle reactive detail: `switchIfEmpty` is applied while obtaining `Caller`.
Applying it to the downstream `Mono<Void>` would mistake normal empty completion
for missing authentication. Empty completion and absence of an identity value
are different at those two stages.

**Checkpoint:** If Alice refreshes a token with only read scope, can she reuse her
session? Yes. Can that session preserve her old write permission? No: current
caller scopes are checked for each invocation. Session ownership is not cached
tool authorization.

## 12. Results, errors and cancellation

**Implemented in supplied code.** `AllocationResult` separates REST outcomes
from MCP serialization. It has `Success(id)`, `ObjectSuccess(fields)` and
`Failure(code)`. Private success values are redacted in `toString()`.

The MCP handler builds successful results from validated public fields with both
text and structured content. Tool failures have `isError:true`, safe JSON text
and **absent structured content**. That preserves the meaning of the advertised
success output schema. The [MCP tools specification](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
distinguishes tool results from protocol-level failures.

### Know which layer rejected the request

| Layer | Example | Observable result | Business REST request? |
| --- | --- | --- | --- |
| Origin | Unapproved browser origin | HTTP 403 | No |
| Authentication | Missing/invalid/expired JWT | HTTP 401 with metadata challenge | No |
| Caller/session admission | Unknown policy subject or foreign session owner | HTTP 403 | No |
| Session admission capacity | Cannot bind a newly created session | HTTP 503 | Not a tool execution |
| SDK dispatch | Unknown tool / invalid protocol operation | SDK-owned protocol/error behavior | No |
| Input validation | Missing field, wrong type, unknown argument | Tool error `INVALID_ARGUMENTS`, `not_attempted` | No |
| Tool authorization | Wrong scope, resource, owner or payment flag | Tool error `ACCESS_DENIED`, `not_attempted` | No |
| REST execution/projection | Deadline, HTTP failure, invalid JSON | Sanitized `UPSTREAM_*` tool error, `unknown` | May have occurred |

The category `allocationOutcome` is used for all current tools, including reads.
It is the preserved response contract rather than a guarantee that every tool
allocates something. Before-execution denials know the backend was not attempted.
Once upstream execution is involved, failure reporting stays conservative.

For example, a nonretryable HTTP failure produces text whose parsed object has
the following fields; member order may vary:

```json
{
  "code": "UPSTREAM_HTTP_ERROR",
  "message": "The backend returned an unsuccessful HTTP status. The operation outcome is unknown.",
  "allocationOutcome": "unknown"
}
```

Backend response bodies, network exception details, origins and credentials are
not copied into this result. The terminal invocation log contains correlation,
tool name, duration and category. It does not contain caller subject/tenant,
arguments, request key, backend body or result ID. Rejections before the handler
do not produce a handler terminal log. This foundation is not a complete audit
pipeline for all denied HTTP requests.

### Cancellation stops waiting; it does not undo a commit

**Recorded mock evidence.** The experiments distinguish:

| Action | Evidence and interpretation |
| --- | --- |
| Dispose the executor subscription | Local cancellation test: further attempts stop; committed fixture result remains |
| Physically reset the active SDK HTTP stream | Running Boot/SDK fixture: handler logs `cancelled`, further attempts stop; commit remains |
| Gracefully close SDK client/session | Exploratory observation: outstanding invocation was not cancelled by this alone |
| Cancel a client future | Not proof of server-side cancellation |

`ResponseLossIT` uses a byte-forwarding TCP bridge and deliberately resets the
active stream. It does not emulate MCP envelopes or introduce a handcrafted
cancellation handler. The supported SDK is retained.

```text
backend commit --> client stream reset --> server publisher cancelled
      |                                      |
      +-- result still exists                +-- local retries stop
```

The total deadline remains the final local bound when cancellation does not
propagate as a caller expects. Neither a timeout nor cancellation proves rollback.
An assistant should communicate uncertainty without automatically creating a
fresh payment request.

**Checkpoint:** Can HTTP 200 carry an MCP tool error? A transport-level success
can carry an SDK result with `isError:true`. Can `isError:true` imply rollback?
No. Separate transport, protocol, tool and business outcome reasoning.

## 13. Capacity and operational tradeoffs

**Implemented in supplied code.** The executor configuration bounds connections
and waiting acquisition, not every resource in the process:

```java
return ConnectionProvider.builder("worker-coordinator")
        .maxConnections(16)
        .pendingAcquireMaxCount(32)
        .pendingAcquireTimeout(properties.upstream().connectTimeout())
        .build();
```

These are **per connection-pool** settings. A shared `ConnectionProvider` can
manage pools for different destinations; its name does not make sixteen a
global gateway concurrency limit. See the
[Reactor Netty connection-pool API](https://projectreactor.io/docs/netty/1.3.7/api/reactor/netty/resources/ConnectionProvider.ConnectionPoolSpec.html).
There is no explicit global invocation semaphore or per-tenant rate limiter.

### Illustrative sizing, not a benchmark

For one destination, assume HTTP/1.1 requests occupy one connection each and an
average attempt occupies it for 100ms:

```text
connection-limited attempt throughput ≈ 16 / 0.100s = 160 attempts/s

if an invocation averages two attempts:
approximate logical throughput ceiling ≈ 160 / 2 = 80 invocations/s

at 500ms occupancy instead:
16 / 0.500s = 32 attempts/s
```

These are simplified capacity estimates. They exclude CPU parsing, network and
backend variation, SDK overhead, burst arrivals, connection setup and backoff.
Backoff consumes invocation time even while no connection is occupied. They are
not measured throughput, an SLO or evidence that the coordinator sustains that
load.

The per-pool pending count bounds simultaneous queued acquisitions, not all
in-flight invocations waiting in backoff. The 2s acquisition timeout and total
5s deadline limit waiting. More queued requests would not create backend capacity.
Current generic error classification can treat acquisition failure as unavailable
and retry it within the deadline; there is no separately exposed overload result.

For body sizing:

```text
16 simultaneously read responses × 65536 bytes = 1 MiB raw body bytes
```

That is a raw-byte illustration for one pool, not a heap limit. Buffers, copied
byte arrays, decoded strings, parsed trees/maps, additional pools, inbound data
and active sessions add memory. A successful-response byte limit is not proof
of a total process memory bound.

The 256 secured sessions cap identity/session state rather than the throughput
of tool calls. The 15-minute idle setting is unrelated to the 5s REST deadline
or JWT expiry. Similarly, the 3s JWT decode bound is before SDK execution and is
not necessarily included in the SDK's 10s request timer. Do not promise an exact
whole-client-request maximum by merely comparing those two execution budgets.

### Why the current choices are reasonable, and where they stop

| Choice | Benefit now | Limit / future design question |
| --- | --- | --- |
| Static catalog/policy snapshots | Reproducible startup and auditable mapping | Updates require restart and invalidate local sessions |
| One endpoint / logical SDK server | Simple lifecycle and interoperability | Tool groups share discovery and process resources |
| Exact input mapping and output projection | Predictable public contract | New backend shapes require intentional adaptation |
| Shared bounded HTTP provider | Reuses connections and caps each pool | Does not enforce tenant fairness or global call admission |
| Explicit bounded retries | Controlled transient recovery | Can amplify overload; not every backend operation deduplicates |
| Separate caller/REST credentials | Clear trust boundary | Backend still needs its own access/tenant design |
| Loopback listener | Bounded local learning/development | External exposure, TLS and HA require separate deployment work |

**Described but not implemented.**
[ADR 0005](decisions/0005-multiple-virtual-servers.md) proposes separate SDK servers
and transports in one Boot process, with their own catalogs/endpoints/sessions.
It is not enabled by the current version-3 combined catalog. `/payments/mcp`
is not a currently implemented second endpoint.

**Production enhancement.** Candidate changes include explicit global/per-tenant
admission limits, backend-supported replay contracts, observable retry/pool
metrics and a selected HA/session strategy. Add each only after defining the
invariant and tests it must satisfy. Increasing pool size or adding a load
balancer alone does not supply those guarantees.

**Checkpoint:** Which limits are per response, per pool, per invocation and per
session registry? Explain all four before estimating capacity. The common
mistake is using one bound as if it capped the entire application.

## 14. How the tests establish evidence

**Recorded mock evidence.** The final foundation command was:

```powershell
.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' verify
```

It passed 121 Surefire cases and 20 Failsafe cases, with zero failures, errors or
skips, and produced the executable JAR. It did not run `clean`, delete work or
overwrite the separate running Windows demo's artifact. Full CI commands were
restored, but no hosted CI execution is claimed.

`*Test` classes are Surefire tests; `*IT` classes run through Failsafe during
`verify`. Parameterized cases contribute to totals; class or method counts alone
are not the 141 executed-case count. Retained Surefire reports can include earlier
focused `*IT` runs. Use the final Maven summaries rather than summing every old
XML file in an output directory that was deliberately not cleaned.

### Why the mocks are independent

```text
real SDK client
      |
      | actual network protocol
      v
running Boot server on random loopback port
      |
      +--> independent JDK HTTP REST fixture on random port
      |
      +--> independent local issuer/JWKS listener on random port
```

The fixtures do not reuse the production gateway's parser to construct expected
HTTP requests. They record requests, increment counters and deliberately control
responses. Assertions compare exact path/body/header values and subsequent
absence of additional attempts. No automated test allocates live IDs.

| Test source | What to look for |
| --- | --- |
| [ScaffoldStartupTest](../src/test/java/dev/mcp/gateway/ScaffoldStartupTest.java) | Default MCP stays disabled; startup does no allocation |
| [GatewayPropertiesTest](../src/test/java/dev/mcp/gateway/config/GatewayPropertiesTest.java) | Unsafe origins/tokens/settings rejected; private values redacted |
| [FileCatalogLoaderTest](../src/test/java/dev/mcp/gateway/catalog/FileCatalogLoaderTest.java) | Immutable supported catalogs, exact types/epochs and invalid mappings |
| [RestBindingTest](../src/test/java/dev/mcp/gateway/rest/RestBindingTest.java) | URI/path/pointer safety |
| [RestBindingExecutorTest](../src/test/java/dev/mcp/gateway/rest/RestBindingExecutorTest.java) | Lazy execution, precise IDs, bytes/deadlines, controlled retries, sanitization |
| [CatalogProtocolIT](../src/test/java/dev/mcp/gateway/mcp/CatalogProtocolIT.java) | Legacy SDK discovery/results, restart snapshots, startup failures, discovery during outage |
| [CoordinatorToolsIT](../src/test/java/dev/mcp/gateway/mcp/CoordinatorToolsIT.java) | All ten discovered; nine exact REST mappings; separate payment token/origin; Origin and argument errors |
| [SecurityPolicyTest](../src/test/java/dev/mcp/gateway/security/SecurityPolicyTest.java) | Secure settings, invalid/ambiguous policy and immutable policy snapshots |
| [SecuredGatewayIT](../src/test/java/dev/mcp/gateway/mcp/SecuredGatewayIT.java) | JWT matrix, scopes, tenant/owner/parent denials, session ownership, concurrent contexts, forged metadata |
| [ResponseLossIT](../src/test/java/dev/mcp/gateway/mcp/ResponseLossIT.java) | Commit/loss/retry recovery, deadline unknown and active SDK stream reset |
| [CommittedCancellationTest](../src/test/java/dev/mcp/gateway/rest/CommittedCancellationTest.java) | Local subscription cancellation stops retries without rollback |

Read assertions before implementation when studying a rule. For example:

- `discoveryExposesTenPublicDefinitionsWithoutCallingEitherBackend` checks names,
  absence of private fields/credentials and both backend counters at zero.
- `mapsEveryCoordinatorApiAndKeepsLeaseEpochExact` checks all mappings, exact
  epoch and public projection, including extra/private/null upstream fields.
- `enforcesCompleteTenantNamespaceAndOwnerRelationships` exercises relationship
  combinations that a simple single-tenant happy-path test would miss.
- Response-loss tests compare complete repeated requests, distinguish attempt
  counters from logical commits and wait boundedly to prove attempts stop.

Direct executor/policy tests prove local mechanics. Running SDK/Boot tests prove
that those mechanics are reached through the actual protocol and security route.
A passing startup test alone cannot establish that.

The separate-process packaged smoke launched the final executable and standalone
mock on random ports: SDK discovery produced zero allocations; two explicit
calls produced two allocations with distinct backend IDs and equal parsed
text/structured output. This adds packaging evidence to in-JVM integration tests.

### What these results do not establish

They do not establish live backend failover, production isolation or an
interactive OAuth authorization-code/PKCE journey. Historical live payment
success and shortener/HAProxy discovery remain different evidence. Current
authentication tests use synthetic signed tokens from a local issuer. The
[acceptance tracker](ACCEPTANCE.md), [secured guide](SECURED_MCP.md) and
[worklog](../WORKLOG.md) distinguish these boundaries.

**Checkpoint:** Would two REST requests automatically prove a retry bug? No;
controlled application retries are expected for selected failures. Would two
logical commits under one supported payment key be acceptable? That would
violate the fixture's modeled deduplication guarantee. Count the right thing.

## 15. Run an independent local demonstration

These commands are instructions for you to execute. Keep the existing live demos
separate; the tutorial's allocation exercise must target the standalone mock.
Use the [mock guide](MOCK_DEMO.md) for the compact runbook.

### Build and resolve the SDK client classpath

From the repository root in PowerShell:

```powershell
pwsh -NoProfile -File scripts/doctor.ps1
.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' verify
.\mvnw.cmd -B -ntp dependency:build-classpath '-Dmdep.outputFile=target/foundation-runtime-classpath.txt' '-Dmdep.includeScope=runtime'
```

The separate build directory avoids touching the existing demo's locked JAR.
Do not run `clean` or delete artifacts as part of this exercise.

### Terminal 1: independent REST mock

```powershell
java -cp target/foundation/classes dev.mcp.gateway.demo.StandaloneRestMock 19090
```

This JDK HTTP server supplies synthetic IDs and exposes `/stats`. Only this mock
generates the `worker-N` value. It has no durable storage or caller security.

### Terminal 2: gateway on another port

```powershell
java -jar target/foundation/mcp-gateway-server-0.1.0.jar --spring.profiles.active=mock --server.port=18080 --gateway.backends.worker-coordinator.base-url=http://127.0.0.1:19090
```

The explicit origin selects the standalone mock even if the shell has a different
backend environment setting. Ports 19090/18080 are examples. If occupied, select
unused ports consistently, or use mock argument `0` and gateway `--server.port=0`
and read their assigned ports from startup output. Tests always use random ports.

### Terminal 3: discover, then make two explicit calls

Use a shell without `MCP_ACCESS_TOKEN` for this unauthenticated exercise.

```powershell
$gatewayClasspath = 'target/foundation/classes;' + (Get-Content target/foundation-runtime-classpath.txt -Raw).Trim()
java -cp $gatewayClasspath dev.mcp.gateway.demo.McpSmokeClient --base-url=http://127.0.0.1:18080
Invoke-RestMethod http://127.0.0.1:19090/stats
```

Expected: `allocate_worker_id` discovered, `Discovery only`, allocations `0`.

```powershell
java -cp $gatewayClasspath dev.mcp.gateway.demo.McpSmokeClient --base-url=http://127.0.0.1:18080 --allocate --count=2
Invoke-RestMethod http://127.0.0.1:19090/stats
```

Expected on a fresh mock: two completed calls, two distinct returned IDs, matching
text/structured content and allocations `2`. The SDK client suppresses ID printing.
Use Ctrl+C in the two exercise terminals to stop only those owned processes.

Do not substitute the live payment/coordinator origin into this allocation
exercise. Discovery on another server is separate from permission to mutate it.
On POSIX, use `sh ./mvnw` and a `:` classpath separator.

### Inspector for the URL shortener through HAProxy

The saved [Inspector guide](MCP_INSPECTOR.md) has full endpoint choices and
troubleshooting. Node 22.19.0 or newer is required for the pinned Inspector.
Run:

```powershell
npx --yes @modelcontextprotocol/inspector@2.9.0 --web --transport http --server-url http://127.0.0.1:8119/mcp --protocol-era legacy
```

Open the token-bearing browser URL printed by the terminal. Connect and list
Tools; expect `create_short_link` and `get_link`. For this local shortener demo,
leave custom Origin and Authorization headers unset. If Inspector is already
running on port 6274, reuse its UI and select the shortener URL rather than
launching another copy. See the [official Inspector documentation](https://modelcontextprotocol.io/docs/tools/inspector).

`--protocol-era legacy` selects the protocol family used by these recorded
servers, including `2025-11-25`; it does not mean the old HTTP+SSE transport.
`--transport http` here selects Streamable HTTP.

For CLI discovery without creating a link:

```powershell
npx --yes @modelcontextprotocol/inspector@2.9.0 --cli --transport http --server-url http://127.0.0.1:8119/mcp --protocol-era legacy --connect-timeout 10000 --method tools/list
```

A direct comparison uses `http://127.0.0.1:8117/mcp`, bypassing HAProxy. Opening
`/mcp` directly in a browser can return 405 because the shortener's stateless MCP
transport uses POST; use Inspector as a protocol client. A 503 through HAProxy
calls for backend readiness investigation, not a change to JSON tool arguments.

`get_link` with an existing code is a read check. `create_short_link` writes to
the shared database and uses owner/url/requestKey plus the advertised optional
fields. An uncertain creation result must be reconciled with the same owner,
request key and exact arguments under the shortener's own replay contract.

### Security/failure evidence without live mutations

Use the full verification command above, or the focused demonstration:

```powershell
.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' '-Dtest=SecurityPolicyTest,CommittedCancellationTest' '-Dit.test=SecuredGatewayIT,ResponseLossIT' verify
```

The tests provision independent HTTP/JWKS fixtures and in-memory signing keys;
you do not need to mint a real token or contact live backends. To run with an
actual external issuer later, use [SECURED_MCP.md](SECURED_MCP.md). Its placeholder
issuer URLs are examples, not provisioned services.

**Checkpoint:** Before invoking anything, name the exact target process and
whether the selected tool reads or mutates state. Discovery should leave the
business counter unchanged. A tool list is not an automatic invocation hook.

## 16. Code reading, debugging and exercises

Use the mock or integration tests when attaching a debugger. Never use a live
backend merely to step through a mutation. Debugger pauses can exceed deadlines;
a timeout while paused is not evidence of an ordinary timing defect.

### Breakpoints for one complete call

| Breakpoint | Inspect privately | Question it resolves |
| --- | --- | --- |
| `FileCatalogLoader.validate` | Version, server, tool names, mapping coverage | Why is this catalog accepted? |
| `McpToolRegistrationConfiguration.specification` | Public SDK Tool versus captured private definition | What gets exposed in discovery? |
| `SessionAdmissionFilter.filter` | Verified principal, server-established Caller | Where does identity originate? |
| `TenantPolicy.allows` | Scope, requested IDs, parent relationship | Why is this call admitted or denied? |
| `McpToolRegistrationConfiguration.invoke` inside `Mono.defer` | Arguments, definition and policy branch | When does application execution actually begin? |
| `RestBinding.resolve` | Concrete path and mapped body | Which argument went where? |
| `RestBindingExecutor.bodyForInvocation` | Whether a payment key is generated | Is key generation once per invocation? |
| `RestBindingExecutor.executeWithBody` inside its `Mono.defer` | Attempt number via fixture counter, unchanged body | Is this a retry or a new invocation? |
| `RestBindingExecutor.read` / `project` | Status, bounded bytes, result variant | Was HTTP successful and did JSON satisfy the binding? |
| `FlatObjectSchema.project` at the handler | Advertised fields and exact numeric types | Why is this upstream field dropped or rejected? |
| Handler `doFinally` | Terminal category/signal | Did work finish, fail or cancel? |

Do not paste debugger captures of tokens, bodies or private IDs into shared logs
or documentation. Method names are the stable navigation anchors here; IDE
Find Symbol is more useful than line numbers that drift as code changes.

### Exercise A: predict the request before stepping

Given `register_service({productId:"p", serviceId:"s", serviceName:"Service"})`,
write down the HTTP path/body, required scope and returned public fields.

**Expected reasoning:** POST `/api/v1/products/p/services`; body contains only
serviceId/serviceName; write scope plus owned matching parents; output serviceId,
productId, serviceName, status. Use `CoordinatorToolsIT` to compare your prediction.

### Exercise B: follow each denial boundary

Predict the results before reading the tests:

| Input/condition | Expected |
| --- | --- |
| Token has read scope, call `register_product` | `ACCESS_DENIED`, `not_attempted`, zero business calls |
| Alice calls `get_product` for `bp` | Same sanitized tool denial |
| Alice registers `s2` under `p` | Parent mismatch; zero business calls |
| Alice uses Bob's owner UUIDs in a lease | Owner denial; zero business calls |
| `get_product` with `productId:"../x"` | `INVALID_ARGUMENTS`; zero business calls |
| Bob's current token + Alice's session ID | HTTP 403 before tool dispatch |
| Expired token + otherwise valid arguments | HTTP 401 before policy/tool execution |
| Caller supplies forged tenant/scopes in MCP `_meta` | No change to server-established identity |

These are secured-profile expectations, except schema validation which applies
in legacy profiles too. Do not infer denied authorization from an unauthenticated
mock profile that intentionally has no tenant policy.

### Exercise C: prove lossless projection

Find the fixture response with `9007199254740993`. Trace the Java numeric type
through strict JSON parsing, output validation and SDK JSON serialization.
Compare with payment's conversion to a string. Explain what a JavaScript host
would need to do to retain exact integer epochs.

### Exercise D: count subscriptions and attempts

Read `constructingExecutorAndPublisherMakesZeroRequestsThenTwoSubscriptionsMakeTwoExactRequests`.
Explain why a publisher is not a cached result. Then read the payment retry test
and identify which defer boundary is resubscribed. Explain how moving
`bodyForInvocation` into that boundary would change the backend's deduplication key.

### Exercise E: reason about response loss before looking at assertions

For DROP_FIRST, predict attempts=2 and commits=1. For DROP_ALWAYS, predict a
bounded variable number of attempts, one retained commit and unknown outcome.
For a physical reset after commit, predict local retries stop but commit persists.
Now compare with `ResponseLossIT`. An exact retry count under jitter is not the
invariant; bounded lifetime and identical attempts are.

### Exercise F: explain a safe extension without implementing it

Suppose you want `get_payment` or another catalog operation. List the new public
schema, safe mapping, output projection, backend reference, permission, tenant
ownership rule and running-SDK evidence. Notice that a secure unknown tool name
fails startup until its authorization behavior is explicit.

**Checkpoint:** You should now be able to open a tool definition and predict its
entire successful request/result plus the places it can be rejected. If you
cannot, return to the earliest untrusted-to-trusted transition you skipped.

## 17. End-to-End Mental Model

There are two main paths and two kinds of state:

```text
STARTUP STATE (immutable)                  REQUEST STATE (per current request)
catalog --> public tools + private maps    token --> verified Caller
policy  --> allowed identity/resources     arguments --> validated values
config  --> origins/tokens/budgets          session ID --> owner admission
          |                                           |
          +---------------------+---------------------+
                                |
DISCOVERY: SDK returns public tools; no business REST work

EXECUTION: SDK dispatch
             --> validate arguments
             --> authorize current Caller + resource
             --> resolve concrete binding
             --> create payment key if needed
             --> bounded explicit HTTP attempts with unchanged values
             --> parse exact data and project public success
             --> SDK result or sanitized conservative error
```

The gateway establishes what is callable and who may attempt it. The backend
owns the business commit and authoritative result. The SDK owns protocol state.
A future host/assistant owns user interaction and orchestration. Their guarantees
must be composed explicitly rather than inherited by proximity.

## 18. End-to-end scenarios

### Normal write

**Implemented in supplied code; recorded mock evidence.** Alice with write
scope registers an allowed service under its allowed parent. Input, scope and
relationship checks pass; a resolved POST reaches the coordinator fixture;
advertised fields return in matching text/structured output. The gateway does
not persist a service locally.

### Normal read

**Implemented in supplied code; recorded mock evidence.** Alice with read scope
gets product `p`. GET has no body and uses the private coordinator credential if
configured. Extra backend fields are dropped. Alice asking for `bp` is denied
before REST. An owned-resource admission does not imply response-level ownership
verification beyond the backend contract.

### Node failure

**Recorded mock evidence for backend outage; HAProxy failure remains unvalidated.**
A stopped REST fixture does not prevent startup/listing, but a tool call retries
unavailable attempts within the deadline and returns a sanitized unknown failure.
If the gateway process itself dies, its local sessions and invocation publishers
are lost. No gateway replica/session continuity experiment is claimed.

A failed shortener replica behind HAProxy is a different path. Discovery through
HAProxy succeeded historically; controlled replica failure/recovery, routing
behavior and in-flight outcomes require the separate planned experiment.

### Slow dependency

**Recorded mock evidence.** A successful status followed by a stalled or slowly
streamed body is still within the total executor deadline and byte limit. The
body must fully satisfy the output contract. A stalled error body is cancelled
without waiting for arbitrary diagnostics. No blocking handler thread is needed
to hold that network wait, but connections and memory are still finite resources.

### Network partition or lost response

**Recorded response-loss evidence; broader partition reasoning is an inference.**
The payment fixture commits and drops the reply. A same-key retry may recover
the original result. If all replies are lost, the gateway reaches its deadline
and reports unknown. This models ambiguity at the commit/response boundary;
it is not a test of all real network partition topologies or database failovers.

### Recovery

**Recorded mock evidence.** DROP_FIRST recovers the same committed result via
the same exact request/key. A fresh invocation creates a different key and can
create another payment. Backend restoration does not retroactively make an
earlier unknown result safe to repeat with a new key. Restarted gateway sessions
must be initialized again. Catalog/policy edits also take effect only after restart.

### Node addition

**Described but not implemented for gateway scaling.** Adding another gateway
process creates another in-memory SDK/admission session store; this code has no
cross-process replication. A routing/session design and interoperability/failure
tests would be needed before promising active-session continuity. The proposed
multiple virtual servers share one JVM and are not this HA design.

For native shortener replicas, HAProxy can be part of their independent topology;
this gateway does not register them or become their discovery/control plane.

### Coordinator or control-plane failure

**Implemented separation; some consequences are design inference.** A coordinator
REST outage affects namespace/lease execution, while the separate payment origin
and catalog discovery have separate dependencies. The fixture suite demonstrates
independent origin/credential selection; it does not promise cross-service SLOs.

There is no Nacos or runtime catalog control plane in this gateway. A missing or
invalid local catalog/policy fails startup. Once loaded, those immutable snapshots
do not depend on continual file reads. The authorization server's JWKS is an
authentication dependency; availability during its outage depends on verification
material/cache behavior and should be tested separately rather than assuming
either universal outage tolerance or universal failure. Interactive login and
revocation behavior remain outside the recorded JWT fixture proof.

## 19. Mechanism to question map

| Mechanism | Question it answers |
| --- | --- |
| SDK initialization/capabilities | Can these client/server implementations speak a compatible protocol? |
| Startup catalog validation | Are the supported public schema and private binding coherent? |
| Public Tool construction | What may a client discover without learning backend details? |
| Origin filter | Is this browser-origin request admitted to the MCP endpoint? |
| JWT signature/issuer/audience/time validation | Is this a current credential for this configured resource? |
| Immutable Caller transport context | Which verified identity accompanies this particular request? |
| Scope and tenant policy | May that identity attempt this operation on these resources? |
| Session owner registry | Does the current caller own this SDK session identifier? |
| Explicit argument mapping and safe URI resolution | Which fixed REST operation will these values become? |
| Exact integer parsing and output projection | Does the response satisfy the advertised public success contract? |
| Connection/queue/byte/deadline bounds | How much local waiting/work can this executor sustain or permit? |
| Same invocation body/key across retries | Can the backend reconcile these attempts as one supported operation? |
| Unknown-outcome reporting | What can the gateway honestly conclude after upstream ambiguity? |
| Independent request/commit counters | Did the experiment make attempts, commits or no backend work? |
| SDK/Boot integration tests | Do the local rules operate through the actual protocol stack? |

## 20. Key Concepts to Retain

1. MCP discovery exposes callable contracts; invocation performs work. They have
   different dependencies and must be tested separately.
2. A server, client, host, REST backend and model have distinct responsibilities.
   The operations assistant belongs in its own future repository.
3. Startup metadata is immutable. Current request identity and permissions are
   re-established at the authenticated transport boundary.
4. Validation checks shape; authentication checks credentials; authorization
   checks permitted operations/resources. None replaces the others.
5. Policy relationships and owner UUIDs constrain admission; backend storage and
   direct access remain separate security responsibilities.
6. The SDK owns protocol machinery. The gateway owns catalog adaptation, bounded
   REST execution and safe application results.
7. Identical retries require stable invocation values. Stable values still need
   a backend-supported idempotency contract to deduplicate commits.
8. A generated payment key is scoped to one invocation. New calls are not replay
   recovery under the old key.
9. Success returns exact advertised data. Errors omit success structured content
   and disclose neither secrets nor uncertain guarantees.
10. Timeout/cancellation can stop local work without undoing a remote commit.
11. Per-pool, per-body, per-invocation and per-session bounds are different.
12. Mock proof, historical live proof, proposed architecture and production
    guarantees must remain distinguishable.

## 21. Principal Engineer drill-down questions

Use these to test whether you can defend the design rather than only repeat
class names. Each question includes the boundary your answer should address.

| Question | A strong answer addresses |
| --- | --- |
| Why not make the coordinator itself depend on Spring AI? | Existing REST ownership, adapter boundary and independent upgrade/deployment concerns |
| Why not handcraft a JSON-RPC controller? | Lifecycle/session/version/error interoperability owned by the SDK |
| Why disable SDK input validation? | Sanitized application errors while preserving strict validation in the handler |
| Can a discovered read-only hint authorize a caller? | Hints versus enforced identity/scope/resource policy |
| What if a token is correctly signed but has the wrong audience? | Resource-specific authentication rejection before dispatch |
| How does a refreshed token interact with a session? | Stable owner tuple, current scopes/expiry, no cached write privilege |
| What does tenant isolation actually cover? | Gateway admission, trusted parent maps and owners; explicit backend/direct-access limits |
| What if the backend returns an unrelated but schema-valid ID? | Projection's limits; response-integrity contract is not currently checked |
| Why is `Mono.defer` placement consequential? | Subscription laziness and stable request-key lifetime across retry resubscriptions |
| Does a 5s deadline imply a failed payment did not commit? | Remote commit/response ambiguity and conservative unknown results |
| When would retries worsen availability? | Overload amplification, queue contention, retry budgets and missing fairness controls |
| Is maxConnections=16 a global limit? | Per-destination pools, active invocations in backoff and absent global semaphore |
| What is needed for safe replay after process death? | Durable caller-key contract, backend reconciliation and host behavior |
| Can HAProxy make stateful gateway sessions portable? | SDK/admission stores, routing strategy and explicit failover evidence |
| What must be proven before multi-server implementation? | Separate discovery/routes/context/sessions plus lifecycle/resource isolation tests |
| What must an assistant communicate after an unknown outcome? | The uncertainty, need for reconciliation and absence of safe fresh-call replay |

## 22. References used

### Repository authority and current evidence

- [Functional specification](FUNCTIONAL_SPEC.md), [design](DESIGN.md),
  [implementation prompt](IMPLEMENTATION_PROMPT.md) and
  [implementation plan](IMPLEMENTATION_PLAN.md): original milestone and staged work.
- [Acceptance](ACCEPTANCE.md) and [WORKLOG](../WORKLOG.md): exact executed evidence
  and residual limits. Later user-directed changes are recorded in ADRs.
- [ADR 0002](decisions/0002-user-authorized-allocation-retries.md): explicit retry decision.
- [ADR 0003](decisions/0003-local-payment-mcp-demo.md): separate payment contract.
- [ADR 0004](decisions/0004-worker-api-tools.md): version-3 coordinator tools and exact lease output.
- [ADR 0005](decisions/0005-multiple-virtual-servers.md): proposed, unimplemented multi-server architecture.
- [ADR 0006](decisions/0006-secured-mcp-foundation.md): secured foundation and limits.
- [Dependency baseline](DEPENDENCY_BASELINE.md): resolved/pinned API inspection;
  historical stage statements must be read alongside current evidence.
- [Worker tools](WORKER_TOOLS.md), [mock demo](MOCK_DEMO.md),
  [secured guide](SECURED_MCP.md), [threat model](SECURITY_THREAT_MODEL.md),
  [Inspector guide](MCP_INSPECTOR.md): operation and security boundaries.
- Java sources, catalogs and tests linked at each walkthrough step are the source
  for implementation-specific claims; tutorial JSON examples are illustrative
  values matching those contracts.

### Primary external references

- [MCP 2025-11-25 architecture](https://modelcontextprotocol.io/specification/2025-11-25/architecture),
  [lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle),
  [tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools),
  [transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
  and [authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization).
- [Spring Security reactive JWT resource server](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html).
- [Reactor Mono API](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Mono.html)
  and [Reactor Netty 1.3.7 connection pools](https://projectreactor.io/docs/netty/1.3.7/api/reactor/netty/resources/ConnectionProvider.ConnectionPoolSpec.html).
- [Official Inspector documentation](https://modelcontextprotocol.io/docs/tools/inspector).
  Inspector is pinned to the version/flags recorded in the repository guide;
  current general documentation does not override the application's pinned SDK.

The remaining MCP learning proofs are the interactive host/Inspector flow and
controlled HAProxy failure/recovery. Once those are selected and evidenced, the
separate operations assistant can build on this foundation with explicit tool
permissions, result handling and uncertain-outcome behavior.
