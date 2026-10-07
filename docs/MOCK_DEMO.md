# Independent standalone REST → MCP demo

This original worker allocation contract is synthetic. It does not contact the
coordinator, payment worker, shortener or HAProxy. The mock owns generated IDs.
The default application still disables MCP; opt in with `mock`.

From the repository root, build in a separate output directory so the existing
running Windows JAR remains untouched:

```powershell
.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' verify
.\mvnw.cmd -B -ntp dependency:build-classpath '-Dmdep.outputFile=target/foundation-runtime-classpath.txt' '-Dmdep.includeScope=runtime'
```

Terminal 1 (independent backend, loopback 19090):

```powershell
java -cp target/foundation/classes dev.mcp.gateway.demo.StandaloneRestMock 19090
```

Terminal 2 (gateway, loopback 18080):

```powershell
java -jar target/foundation/mcp-gateway-server-0.1.0.jar --spring.profiles.active=mock --server.port=18080
```

Terminal 3 (real SDK client):

```powershell
$gatewayClasspath = 'target/foundation/classes;' + (Get-Content target/foundation-runtime-classpath.txt -Raw).Trim()
java -cp $gatewayClasspath dev.mcp.gateway.demo.McpSmokeClient --base-url=http://127.0.0.1:18080
Invoke-RestMethod http://127.0.0.1:19090/stats # allocations=0
java -cp $gatewayClasspath dev.mcp.gateway.demo.McpSmokeClient --base-url=http://127.0.0.1:18080 --allocate --count=2
Invoke-RestMethod http://127.0.0.1:19090/stats # allocations=2
```

Stop only these terminal processes with Ctrl+C. `StandaloneRestMock 0` and
`--server.port=0` select random ports; startup output reports them. Override
`MOCK_BACKEND_BASE_URL` for the mock profile when using a random backend port.
Classpath separators are `:` on POSIX; Wrapper command is `sh ./mvnw`.

Discovery loads immutable catalog metadata and does no backend work. Explicit
allocation resolves POST `/workers/ids` with `{}`, reads a bounded response and
returns exactly the mock ID in matching text/structured content. The gateway
neither generates nor caches returned IDs. Catalog edits require restart.

The mock exposes `/health` and `/stats` outside MCP for process-smoke evidence.
It has no authentication or durable storage. The mock profile is an unauthenticated
local demo; use the separate [secured foundation](SECURED_MCP.md) for caller policy.
Keep the original retry/unknown-outcome limitations from ADR 0002. Do not change
this mock origin to a live backend while exercising automated demonstrations.
