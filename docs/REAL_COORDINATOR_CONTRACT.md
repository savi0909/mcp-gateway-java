# Inspected local coordinator contract

Inspected on 2026-10-07 at the user's request, in
`D:/java-projects/distributed-coordinator`. This service does not implement the
gateway specification's assumed `POST /workers/ids` endpoint and does not expose
a UUID-generating REST operation. Do not point the default catalog at lease
acquisition and describe it as UUID generation.

## Available operations

Source: `coordinator-service/.../api/controller/RegistrationController.java` and
`WorkerController.java` in the coordinator repository.

| Method | Path | Behavior |
| --- | --- | --- |
| POST | `/api/v1/products` | Register a product |
| GET | `/api/v1/products/{productId}` | Get a product |
| POST | `/api/v1/products/{productId}/services` | Register a service |
| GET | `/api/v1/services/{serviceId}` | Get a service |
| POST | `/api/v1/services/{serviceId}/worker-types` | Register a worker type |
| GET | `/api/v1/worker-types/{workerTypeId}` | Get a worker type |
| POST | `/api/v1/workers/acquire` | Acquire or recover a currently valid worker-slot lease |
| POST | `/api/v1/workers/renew` | Renew a lease matching owner and epoch |
| POST | `/api/v1/workers/release` | Release a lease matching owner and epoch |

Acquisition requires `productId`, `serviceId`, `workerTypeId`, `regionId`,
`instanceId` and `registrationId`. Both UUID fields are supplied by the caller.
The response contains namespace fields, a numeric `workerId`, `epoch`,
`instanceId`, `leaseExpiry` and `leaseDuration`. `PostgresWorkerCoordinator.acquire`
returns the existing unexpired lease for the same instance. Slots can be reused
after expiry or release. This is not a new globally unique UUID on every call.

The separate `worker-id-client` library generates numeric Snowflake-style IDs
locally after acquiring a lease. `worker-client-sample` exposes
`POST /api/v1/payments`, returning a numeric payment ID; its UUIDv7 idempotency key
comes from the request. The sample's `UuidV7.random()` utility has no REST endpoint.

After reviewing this distinction, the user accepted the separate payment API as
the sample operation for MCP. The opt-in `payments` profile now exposes one
`create_sample_payment` tool. It accepts empty arguments, sends the catalog's
static amount plus a fresh UUIDv7 `clientIdempotencyKey`, and projects numeric
`/id` to the exact string `paymentId`. Automatic retries reuse the same request
key; new invocations use new keys. Default MCP remains disabled and the supplied
worker catalogs remain unchanged.

## Separate Docker coordinator

The user subsequently requested worker APIs as MCP tools. All nine operations
above now have definitions and explicit argument mappings in schema-version-3
catalogs. The `workers` profile exposes those tools; `worker-payments` also retains
the payment tool with a separate private backend origin. See
[WORKER_TOOLS.md](WORKER_TOOLS.md) and [ADR 0004](decisions/0004-worker-api-tools.md).
Actual SDK discovery listed all ten tools in the combined profile; worker
execution tests compile but remain unexecuted under the user's test pause.

The gateway repository's [Compose file](../compose.coordinator.yml) runs the
unchanged coordinator artifact with its own PostgreSQL database and retained
volume. It binds only `127.0.0.1:9090`; PostgreSQL has no published host port.
The payment sample runs separately at `127.0.0.1:9091` against a separate
`payments` database in that PostgreSQL instance. No source file in the coordinator
repository was changed. Both health endpoints returned HTTP 200, `UP`.

The payment worker registers its own demo namespace and acquires/renews a lease
at startup, as its existing implementation requires. Gateway startup and SDK
discovery left the payment table empty. Two actual SDK tool calls subsequently
created two persisted payments with two distinct IDs and request keys. Negotiated
MCP protocol: `2025-11-25`; text and structured outputs matched. These are live
checks of local Docker services, not automated test-suite or production evidence.

Build the artifact without running tests, from the coordinator repository:

```powershell
mvn -B -ntp -pl coordinator-service -am '-Dmaven.test.skip=true' package
mvn -B -ntp -pl worker-client-sample -am '-Dmaven.test.skip=true' package
```

The ignored `config/local/coordinator.env` supplies `COORDINATOR_PROJECT_DIR`,
`COORDINATOR_HTTP_PORT` and a private `COORDINATOR_POSTGRES_PASSWORD`. It was
generated locally; its credential value is not recorded here. Set those variables
in a private env file when reproducing this on another machine.

From the gateway repository:

```powershell
docker compose --env-file config/local/coordinator.env -f compose.coordinator.yml up -d --build
docker compose --env-file config/local/coordinator.env -f compose.coordinator.yml ps
```

The containers are left running. `docker compose --env-file
config/local/coordinator.env -f compose.coordinator.yml stop` stops them while
preserving data. Do not remove containers, volumes or directories without the
user's explicit permission.

For a pre-existing volume created before the payment database initializer was
added, create the `payments` database once if missing. Do not remove the volume to
rerun initialization. See the current worklog for the non-destructive command used
on this machine. Fresh volumes run `scripts/docker-init/02-payments.sql`.
