# S0 SDK compatibility experiment

This standalone test project is excluded from the product Maven build. It pins
Boot 4.0.8 and Java SDK 2.2.0-M1 to investigate the modern SDK servlet independently
of the gateway's pinned WebFlux runtime. The nested `starter/` project checks
Spring AI 2.0.2 or 2.1.0-RC1 without SDK overrides. It is not a migrated gateway.

Read [the findings](../../docs/S0_COMPATIBILITY_FINDINGS.md),
[requirement inventory](../../docs/MCP_2026_REQUIREMENTS.md) and
[ADR 0014](../../docs/decisions/0014-modern-protocol-compatibility-proposal.md)
for outcomes and limitations. All modern product acceptance remains Pending.

From the repository root:

```powershell
python experiments/s0-sdk-compatibility/inspect.py
.\mvnw.cmd -B -ntp -f experiments/s0-sdk-compatibility/pom.xml verify
.\mvnw.cmd -B -ntp -f experiments/s0-sdk-compatibility/starter/pom.xml verify
.\mvnw.cmd -B -ntp -f experiments/s0-sdk-compatibility/starter/pom.xml '-Dspring-ai.version=2.1.0-RC1' verify
```

Builds write only below the root ignored `target/` directory. The inspection
script downloads exact official candidate POMs/source JARs/configuration metadata
and dated protocol pages, with a timestamped SHA-256 input manifest. It also
rechecks current metadata; newer releases are reported, never selected
automatically. Python uses its standard library. No global Maven configuration,
product dependency or existing demo is modified; no deletion/clean is performed.

The server probes use SDK-owned parsing/dispatch over running Boot/Tomcat on
random loopback ports, synthetic reads and fixed test-only credential mappings.
Positive modern requests are HTTP/raw-socket fixtures serialized with official
SDK types, because this Java release has **no modern client**. Official-client
tests prove a legacy round trip and failure against the independent modern
server. No handwritten JSON-RPC server or runtime compatibility shim exists.

Two tests intentionally characterize gaps: duplicate conflicting method headers
and mismatched custom parameter headers currently execute. Their passing result
means the blocker was reproduced. They must become rejection tests before
selecting a modern product integration. Other tests verify discovery/list/read,
concurrent caller contexts, rejected requests, JSON/SSE and reactive disconnect
cancellation. Timeouts, counters and controlled stream completion keep the
experiment bounded. The test-only modules package empty JARs by design.
