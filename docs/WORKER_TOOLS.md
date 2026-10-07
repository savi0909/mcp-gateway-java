# Worker Coordinator MCP tools

The combined `worker-payments` profile exposes all nine inspected coordinator
REST APIs alongside `create_sample_payment`. All tools use the existing MCP
Streamable HTTP endpoint: `http://127.0.0.1:8080/worker-coordinator/mcp`.
The coordinator and payment worker remain unchanged, separate REST services.

| Tool | REST operation | Required arguments |
| --- | --- | --- |
| `register_product` | POST `/api/v1/products` | `productId`, `productName` |
| `get_product` | GET `/api/v1/products/{productId}` | `productId` |
| `register_service` | POST `/api/v1/products/{productId}/services` | `productId`, `serviceId`, `serviceName` |
| `get_service` | GET `/api/v1/services/{serviceId}` | `serviceId` |
| `register_worker_type` | POST `/api/v1/services/{serviceId}/worker-types` | `serviceId`, `workerTypeId`, `workerTypeName` |
| `get_worker_type` | GET `/api/v1/worker-types/{workerTypeId}` | `workerTypeId` |
| `acquire_worker` | POST `/api/v1/workers/acquire` | `productId`, `serviceId`, `workerTypeId`, `regionId`, `instanceId`, `registrationId` |
| `renew_worker_lease` | POST `/api/v1/workers/renew` | Acquire arguments plus `workerId`, `epoch` |
| `release_worker` | POST `/api/v1/workers/release` | Acquire arguments plus `workerId`, `epoch` |
| `create_sample_payment` | POST `/api/v1/payments` | Empty object; combined profile only |

Definitions, descriptions, schemas, hints and private mappings are in
[coordinator-catalog.json](../examples/coordinator-catalog.json) (nine tools) and
[coordinator-payment-catalog.json](../examples/coordinator-payment-catalog.json)
(ten tools), with matching packaged copies. Catalog edits require restart.

## Run and discover

The gateway currently runs the combined profile. Its PID is in ignored
`target/worker-tools-gateway.pid` and `target/payment-gateway.pid`. Build to a
separate directory and use a separate loopback port to preserve that process. See
[PAYMENT_DEMO.md](PAYMENT_DEMO.md) to reproduce the separate Docker services.

```powershell
# Tests have been explicitly resumed; this leaves the existing demo JAR untouched.
.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' verify
java -jar target/foundation/mcp-gateway-server-0.1.0.jar --spring.profiles.active=worker-payments --server.port=18080
# Alternatively, expose only nine coordinator tools:
# java -jar target/mcp-gateway-server-0.1.0.jar --spring.profiles.active=workers
```

From another gateway-project terminal, discover without invoking any tool:

```powershell
.\mvnw.cmd -B -ntp dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt' '-Dmdep.includeScope=runtime'
$gatewayClasspath = 'target/classes;' + (Get-Content -LiteralPath target/runtime-classpath.txt -Raw).Trim()
java -cp $gatewayClasspath dev.mcp.gateway.demo.McpSmokeClient
```

The smoke client's explicit `--allocate` option still creates payments when that
tool is present. It never chooses a lease tool implicitly. Use an MCP client's
named tool invocation with the advertised schemas to operate leases.

## Contract and configuration

Setup/lookup identifiers match `^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}$`, deliberately
confining URL identifiers to one safe segment. Names are nonblank strings up to
255 characters. Lease namespace IDs are nonblank strings up to 100 characters;
`regionId` is an integer from 0 to 15, `workerId` a nonnegative signed 32-bit
integer, and `epoch` a positive signed 64-bit integer. Ownership UUIDs use
canonical UUID syntax. All input fields are required; additional fields fail
before REST execution. Acquire arguments have this shape:

```json
{
  "productId": "my-product",
  "serviceId": "my-service",
  "workerTypeId": "my-worker",
  "regionId": 0,
  "instanceId": "00000000-0000-0000-0000-000000000001",
  "registrationId": "00000000-0000-0000-0000-000000000002"
}
```

Register the product, service and worker type before acquisition. Keep the same
owner UUIDs when renewing/releasing, and use the returned `workerId` and `epoch`.
Worker slots can be recovered or reused; they are not newly generated globally
unique IDs.

Registration/lookup results contain corresponding IDs, names, parent ID where
applicable, and status. Acquire/renew results contain `productId`, `serviceId`,
`workerTypeId`, `regionId`, `workerId`, `epoch`, `instanceId`, `leaseExpiry` and
`leaseDuration`. Duration accepts the backend's ISO duration string or JSON number
representation without changing it. Release returns `released` and `epoch`.
Only advertised fields are projected, with matching text and structured content.
Integer IDs and epochs avoid floating-point conversion. Payment output remains
the exact string `paymentId`.

Schema version 3 supports one logical server and 1–32 uniquely named tools,
flat object schemas, explicit `bodyArguments` and `pathArguments`, and fixed
private backend references. Unsupported schemas/fields/mappings, duplicate
keys/names, unconfigured backends, unsafe paths and extra servers fail startup.
This is a limited gateway-owned format, not an arbitrary JSON Schema or OpenAPI
execution engine. Versions 1 and 2 retain their original single-tool behavior.

| Setting | Combined profile default |
| --- | --- |
| `WORKER_COORDINATOR_BASE_URL` | `http://127.0.0.1:9090` |
| `WORKER_COORDINATOR_BEARER_TOKEN` | Absent |
| `PAYMENT_API_BASE_URL` | `http://127.0.0.1:9091` |
| `PAYMENT_API_BEARER_TOKEN` | Absent |
| `GATEWAY_CATALOG_LOCATION` | `classpath:catalog/coordinator-payment-catalog.json` |

Coordinator and optional `payment-api` origins/tokens stay in deployment settings,
separate from public metadata. The legacy `payments` profile binds its sole
backend to the payment origin; use `worker-payments` by itself for the combined
catalog. Clear an old `GATEWAY_CATALOG_LOCATION` override when switching profiles
unless deliberately supplying a compatible external catalog. Default MCP remains
disabled. The `workers` profile uses the coordinator-only catalog.

Loopback listening, Origin enforcement, budgets, response limits, cancellation,
sanitized errors and user-authorized bounded automatic retries remain in effect.
Retries reuse identical owner/epoch/body values; worker ownership UUIDs are
caller supplied. Errors report `allocationOutcome: unknown`; an error does not
establish whether a mutation occurred. Structured content is absent on errors.
`INVALID_ARGUMENTS` reports `not_attempted`. Logs omit IDs and bodies.

## Evidence

Full Wrapper verification passed with 121 unit/startup tests and 20 integration
tests, zero failures/errors/skips. Independent real Boot/SDK tests negotiate
`2025-11-25`, discover all ten tools and verify every coordinator mapping, exact
epochs, private projection and separate backend credentials. No live mutations
were made during foundation verification.

New `FileCatalogLoaderTest` and `CoordinatorToolsIT` cover supported/invalid
catalogs, all nine request mappings, separate backend/token routing, large epochs,
public projection, invalid arguments, sanitized failures and Origin rejection.
They now run as part of full verification against independent random-port mocks.
Worker mutations are mock-verified, not live-verified. See
[ADR 0004](decisions/0004-worker-api-tools.md) and
[the secured foundation](SECURED_MCP.md).
