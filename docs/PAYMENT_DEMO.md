# Run the local payment MCP demo

The running gateway now uses `worker-payments`, exposing nine coordinator tools
alongside the payment tool. See [WORKER_TOOLS.md](WORKER_TOOLS.md). Commands below
still reproduce the original payment-only profile; prior payment smoke results
are historical evidence.

The selected sample API is the existing worker project's `POST /api/v1/payments`.
It generates numeric payment IDs using a coordinator-issued worker lease. These
IDs are not UUIDs. Both existing applications remain unchanged REST services;
only this gateway depends on MCP.

| Component | Local address |
| --- | --- |
| Coordinator REST | `http://127.0.0.1:9090` |
| Payment worker REST | `http://127.0.0.1:9091/api/v1/payments` |
| Gateway MCP Streamable HTTP | `http://127.0.0.1:8080/worker-coordinator/mcp` |

These services are currently running. To reproduce the setup, build the two
existing applications without tests, from the coordinator project:

```powershell
Set-Location D:/java-projects/distributed-coordinator
mvn -B -ntp -pl coordinator-service -am '-Dmaven.test.skip=true' package
mvn -B -ntp -pl worker-client-sample -am '-Dmaven.test.skip=true' package
Set-Location D:/ai-projects/mcp-gateway-server-java
```

A private `config/local/coordinator.env` already exists on this machine. On a fresh
checkout, create it once with a random local database password:

```powershell
if (!(Test-Path -LiteralPath config/local/coordinator.env)) {
    New-Item -ItemType Directory -Force -Path config/local | Out-Null
    $demoPassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    @(
        "COORDINATOR_POSTGRES_PASSWORD=$demoPassword"
        'COORDINATOR_PROJECT_DIR=D:/java-projects/distributed-coordinator'
        'COORDINATOR_HTTP_PORT=9090'
        'PAYMENT_HTTP_PORT=9091'
    ) | Set-Content -LiteralPath config/local/coordinator.env -Encoding utf8
}
docker compose --env-file config/local/coordinator.env -f compose.coordinator.yml up -d --build
docker compose --env-file config/local/coordinator.env -f compose.coordinator.yml ps
```

PostgreSQL uses a retained volume and separate coordinator/payments databases,
with no published database port. A fresh volume runs the payment database
initializer. For a volume from before the initializer was added, check whether
`payments` exists and create it only if missing, as recorded in the worklog. Do
not replace existing data to repeat initialization.

The payment worker registers its own namespace and acquires/renews its lease at
startup. Wait for both `/actuator/health` endpoints to report `UP`. Then build and
start the gateway in another terminal. Stop your running gateway before
rebuilding its JAR on Windows to avoid a file lock.

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.test.skip=true' package
java -jar target/mcp-gateway-server-0.1.0.jar --spring.profiles.active=payments
```

The default profile keeps MCP disabled. The `payments` profile loads the
[payment catalog](../examples/payment-catalog.json) and registers exactly one tool:

| Item | Contract |
| --- | --- |
| Tool | `create_sample_payment` |
| Arguments | `{}` or absent arguments accepted by the SDK |
| Static sample amount | `12.34`, editable in the catalog |
| REST body | Static amount plus a fresh UUIDv7 `clientIdempotencyKey` |
| Response selector | `/id` |
| MCP success | Only `{"paymentId":"<exact backend ID>"}` in matching text and structured content |

Each invocation gets a new request key. Automatic retries reuse the same key and
serialized body, letting the payment API recover an existing payment. The gateway
does not generate the returned payment ID. Schema version 2 adds this narrow
request-key/payment-output variant; the original version-1 worker catalogs are
preserved. Catalog edits require restart.

Use the real SDK client from another gateway-project terminal:

```powershell
.\mvnw.cmd -B -ntp dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt' '-Dmdep.includeScope=runtime'
$gatewayClasspath = 'target/classes;' + (Get-Content -LiteralPath target/runtime-classpath.txt -Raw).Trim()
# Discovery only: creates no payments.
java -cp $gatewayClasspath dev.mcp.gateway.demo.McpSmokeClient
# Explicitly creates two local sample payments.
java -cp $gatewayClasspath dev.mcp.gateway.demo.McpSmokeClient --allocate --count=2
```

An MCP-capable client can connect to the Streamable HTTP address in the table. The
SDK owns initialization, sessions and serialization. The smoke client initializes,
discovers and closes its session with bounded waits. It checks distinct returned
IDs and matching text/structured output, and prints counts rather than IDs.

The current gateway is a hidden Java process whose PID is in ignored
`target/payment-gateway.pid`. Stop only that process when rebuilding. The separate
Docker services can be stopped while preserving their data:

```powershell
docker compose --env-file config/local/coordinator.env -f compose.coordinator.yml stop
```

Actual result: negotiated MCP protocol `2025-11-25`; one discovered tool; zero
persisted payments after gateway startup/discovery; two explicit SDK calls
created two persisted payments with distinct IDs/request keys and matching
text/structured outputs. Final automated tests and Wrapper `verify` remain
deferred at the user's request. Failure-induced retries were not exercised by
this successful live smoke. See [the worklog](../WORKLOG.md) and ADRs
[0002](decisions/0002-user-authorized-allocation-retries.md) and
[0003](decisions/0003-local-payment-mcp-demo.md).
