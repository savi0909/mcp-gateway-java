# S0 protocol and SDK compatibility findings

Completed 2026-10-28 on Java 21.0.9 and Maven Wrapper 3.9.11.
Starting repository commit: `0df6579`. **S0 is complete with a migration blocker.**
Keep the product on Boot 4.0.8, Spring AI 2.0.1 and SDK 2.0.0. No tested combination
supplies both a modern Java client and the required modern WebFlux starter.
S3/S7 and all modern MP acceptance rows remain Pending. Continue with the
roadmap's S1 UC-01 lifecycle and composed queued-revocation slice.

## Support contract and normative inventory

[MCP_2026_REQUIREMENTS.md](MCP_2026_REQUIREMENTS.md) records the server/client
support scope, mandatory core/HTTP/tools/cache/OAuth requirements, conditional
features, schema validation inputs, MP mapping and named planned tests.
The product still supports the tested legacy inbound `2025-11-25` revision and
REST outbound. The target is `2026-07-28` in both native MCP roles, not a
library version called MCP 2.0. Optional capabilities remain disabled.

## Published candidates and exact dependencies

Rechecked Maven Central metadata, exact POMs/source JARs, the official release
API, starter configuration metadata and resolved Maven dependency trees. Central
SDK metadata lastUpdated is `20261016114849`; newest SDK is `2.2.0-M1`, newest
stable SDK is `2.1.0`. Spring AI metadata lastUpdated is `20261022125800`; newest
stable AI is `2.0.2`, newest prerelease is `2.1.0-RC1`. These are observations on
the investigation date, not floating build versions.

| Candidate | Actual dependency/integration evidence | Outcome |
| --- | --- | --- |
| Product: Boot 4.0.8 / AI 2.0.1 / SDK 2.0.0 | Existing WebFlux/ASYNC runtime; fresh full Wrapper verify, 143 unit/startup + 38 integration tests | Preserve; modern APIs are absent |
| Boot 4.0.8 / AI 2.0.2 | Isolated starter resolves SDK 2.1.0; official client negotiates 2025-11-25, lists tool and reads successfully | Viable **legacy experiment**; no modern server registration |
| Boot 4.0.8 / AI 2.1.0-RC1 | Isolated starter also resolves SDK 2.1.0; same successful legacy Boot/SDK read | Prerelease legacy experiment; no modern server registration |
| Boot 4.0.8 / direct SDK 2.2.0-M1 | Direct official modern servlet in embedded Tomcat; modern schema, tools repository, JSON/SSE and disconnect tests compile/run | Viable **bounded server experiment**; no modern Java client or WebFlux binding; not a product migration |

All three inspected AI common configuration metadata artifacts (2.0.1, 2.0.2,
2.1.0-RC1) have protocol/streamable-http endpoint properties and no modern
selection properties. More decisively, their WebFlux auto-configurations import
legacy `io.modelcontextprotocol.spec.McpSchema`; the modern server is not
registered. `STATELESS` in the existing starter is a legacy sessionless mode,
not proof of `2026-07-28` support. No arbitrary SDK override was put into the
product or starter experiment.

For both tested newer starters and the direct modern experiment, Boot resolves:
Spring Framework 7.0.9, Reactor Core 3.8.7, Jackson databind/core 3.1.5,
Jackson annotations 2.21, and networknt schema validator 3.0.6. WebFlux uses
Reactor Netty 1.3.7; the direct modern probe uses Tomcat 11.0.24/Servlet 6.1.
All SDK modules in the direct experiment are 2.2.0-M1.

The [release notes](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.2.0-M1)
describe a Reactor milestone upgrade. However, the **published mcp-core POM**
declares Reactor Core 3.8.7, and the executed Boot dependency tree resolves
3.8.7. Published mcp-json-jackson3 declares Jackson 3.1.4; Boot manages it to
3.1.5. No linkage failure occurred in these probes. This limited result does
not qualify the whole gateway with that preview. The official
[Spring compatibility guide](https://docs.spring.io/spring-ai/reference/getting-started.html)
supports AI 2.0.x with Boot 4.0.x/4.1.x; it does not establish modern SDK/starter
support or the complete product's compatibility with AI 2.1.0-RC1.

## Exact API and integration findings

The SDK 2.2.0-M1 source artifact contains:

- `io.modelcontextprotocol.modern.server.McpServer.builder()` with
  `serverInfo(Implementation)`, `supportedVersions(List<String>)` and
  `feature(McpFeature)`. `handle` owns metadata/version checks and dispatch.
- `ToolsFeature.ofAsync(McpAsyncToolRepository)` and repository
  `list(ctx,cursor)`, `find(ctx,name)`, `call(ctx,request)` methods.
  `McpRequestContext.transportContext()` permits identity-specific catalogs;
  default list cache metadata is private with zero TTL. The feature validates
  input and advertised output schemas through the SDK's validator.
- A separate modern `McpSchema` and `JsonRpc.JSONRPCRequest(jsonrpc,method,id,params)`.
  Legacy schema objects/registration specifications cannot be substituted.
- `McpAsyncResponse.result(...)` and `.streaming(notifier -> Mono<outcome>)`.
  Progress is request-scoped. Servlet stream writes flush and detect I/O failure;
  the transport cancels reactive work after physical disconnect is detected.
- `HttpServletMcpTransport.builder(McpRequestManager)` with `endpoint`,
  `contextExtractor`, `maxRequestSize`, `httpHeaderValidator`, `keepAliveInterval`.
  It owns HTTP/JSON-RPC parsing and errors; authentication belongs in filters.
  Its ordinary servlet path blocks on the container thread, so it cannot simply
  be installed as a WebFlux request handler. A stdio transport also exists.
- **No modern client package/API and no modern WebFlux transport.** The existing
  `client.McpClient` still imports the legacy schema and initializes a session.

The product's `FilteredMcpTransport` installs a legacy `tools/list` handler through
`McpStreamableServerSession.Factory`. `SessionAdmissionFilter` and the UC-01
session-owner filter bind legacy session IDs to trusted callers. A future modern
binding should pass independently verified identity into each repository/call
context while retaining direct admission checks and durable audit. Removing
protocol sessions does not remove authorization, journal state or revocation.
ClientInfo and caller-supplied `_meta` are not verified identity proof.

## Executed experiment results

Sources: [isolated experiment](../experiments/s0-sdk-compatibility/README.md).
All servers are independent running Boot instances on random loopback ports.
All reads are synthetic; no live coordinator, IdP or existing demo is involved.
Tests use fixed local fixture credential-to-caller mappings, **not** the gateway's
JWT/UC-01 implementation. No enterprise acceptance is advanced by them.

The modern Java SDK has no official modern client. Therefore the positive modern
checks use bounded JDK HTTP/raw-socket fixtures serialized using the SDK's modern
JSON-RPC types. These exercise the official SDK server, **not a supported modern
SDK-client round trip**. The official Java-client attempt is separately tested
and fails. No handwritten server/controller or runtime protocol shim was added.

| Named experimental test | Actual result / business counter |
| --- | --- |
| ModernSdkProbeTest.discoverAndListWithoutInitializationOrSessions | HTTP 200 discovery/list; modern revision/complete result; no session header; A-only list with private/0 cache; 0 reads |
| ordinaryReadsKeepTwentyConcurrentCallerContextsIsolated | 20 concurrent A/B list-and-read flows retain caller-specific names and outputs; exactly 20 reads |
| foreignToolAndMissingAuthenticationNeverExecute | Foreign tool errors; invalid fixture credential HTTP 401; 0 reads |
| requiredHeaderAndBodyMismatchesFailBeforeBusinessDispatch | Wrong/missing method, wrong/missing name, mismatched version header: HTTP 400/-32020; 0 reads |
| unsupportedVersionAndMissingMetadataFailBeforeBusinessDispatch | Matching unsupported header/body version: HTTP 400/-32022 with supported version; missing metadata: HTTP 400/-32602; 0 reads |
| encodedNameIsDecodedAndMalformedEncodingRejected | Base64 name succeeds once; malformed encoding gives HTTP 400/-32020; exactly 1 read |
| unknownMethodIs404WithoutBusinessDispatch | HTTP 404/-32601; 0 reads |
| invalidArgumentsAndOriginAreRejectedBeforeExecution | Invalid arguments give isError result; unapproved Origin HTTP 403; 0 reads |
| legacyVerbsAreRejectedAndOfficialJavaClientCannotInitializeModernUpstream | Modern GET/DELETE HTTP 405; official Java client initialization fails, exactly 1 attempted POST, 0 reads |
| sseProgressArrivesBeforeFinalResultWithoutResumptionIds | Progress frame arrives while final result is held behind a test-controlled signal; complete result follows release; no SSE event IDs; exactly 1 read |
| physicalDisconnectCancelsReactiveStreamWithinBound | Physical TCP reset after first progress; reactive cancellation observed within 5 seconds, no normal completion; exactly 1 read |
| previewAcceptsConflictingDuplicateRoutingHeader | **Gap reproduced:** first Mcp-Method value is used; conflicting second value does not prevent HTTP 200 and 1 read |
| previewDoesNotValidateCustomParameterHeaderAgainstBody | **Gap reproduced:** tool schema's x-mcp-header and mismatched Mcp-Param-Scope/body are accepted with HTTP 200 and 1 read |
| LegacySdkProbeTest.previewRetainsLegacyInitializationListingAndOrdinaryRead | Independent official legacy servlet + official client in 2.2.0-M1 negotiate 2025-11-25; discovery 0 reads, explicit call 1 read |
| StarterProbeTest.starterRegistersLegacyServerAndOfficialClientReadWorks | Executed separately with AI 2.0.2 and 2.1.0-RC1; each creates one legacy async server, negotiates 2025-11-25, lists with 0 reads and calls with 1 read |

The two gap tests deliberately assert the preview's existing behavior so the
investigation is reproducible. They are not acceptable product behavior and must
change to fail-closed expectations before migration. Duplicate routing headers
are an explicit gateway MP-03 obligation; this observation is not a claim that
the upstream official conformance suite covers/rejects that case. The custom
header gap agrees with the preview's documented SEP-2243 exclusion.

## Reproduction and evidence

From the repository root, without `clean`:

```powershell
python experiments/s0-sdk-compatibility/inspect.py
.\mvnw.cmd -B -ntp -f experiments/s0-sdk-compatibility/pom.xml verify
.\mvnw.cmd -B -ntp -f experiments/s0-sdk-compatibility/starter/pom.xml verify
.\mvnw.cmd -B -ntp -f experiments/s0-sdk-compatibility/starter/pom.xml '-Dspring-ai.version=2.1.0-RC1' verify
.\mvnw.cmd -B -ntp -f experiments/s0-sdk-compatibility/pom.xml dependency:tree
.\mvnw.cmd -B -ntp -f experiments/s0-sdk-compatibility/starter/pom.xml dependency:tree
.\mvnw.cmd -B -ntp -f experiments/s0-sdk-compatibility/starter/pom.xml '-Dspring-ai.version=2.1.0-RC1' dependency:tree
.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/s0-product-regression' verify
```

Executed final direct experiment: **14 tests, 0 failures/errors/skips**, BUILD
SUCCESS in 7.247s. Each starter experiment: **1 test, 0 failures/errors/skips**,
BUILD SUCCESS (11.310s / 12.738s). Product regression: **143 unit/startup + 38
integration tests, 0 failures/errors/skips**, BUILD SUCCESS in 2:01. The
experiment projects intentionally have only test sources; their empty packaged
JAR warning is expected. They are not product artifacts or root Maven modules.

| Local evidence | Location |
| --- | --- |
| Exact official inputs, SHA-256 digests and retrieval timestamp | target/s0-inspection/inputs.json (70 inputs) |
| Source JARs, extracted source and configuration metadata | target/s0-inspection |
| Direct SDK tree / log | target/s0-inspection/probe-tree.txt; probe-resolve.log |
| Starter trees / logs | target/s0-inspection/starter-2.0.2-tree.txt; starter-2.1.0-RC1-tree.txt; corresponding .log files |
| Modern/legacy probe results | target/s0-inspection/probe-verify.log; target/s0-sdk-probe/surefire-reports |
| Starter result logs | target/s0-inspection/starter-2.0.2-verify.log; starter-2.1.0-RC1-verify.log |
| Starter XML reports | target/s0-starter-2.0.2/surefire-reports; target/s0-starter-2.1.0-RC1/surefire-reports |
| Product regression log and reports/JAR | target/s0-product-regression.log; target/s0-product-regression |

Key immutable input digests (SHA-256):

```text
mcp-core-2.2.0-M1.jar
f630a9cb23436b898cf6e89f8da6d82f22a2f1f4b44a54d3eec9f913040716c7
mcp-core-2.2.0-M1-sources.jar
847e3c0ea4f4d1a3e02dd491398ed2ed489b40b93f7177de0c911c53b9c4732a
spring-ai-autoconfigure-mcp-server-webflux-2.0.2-sources.jar
268f16d6b4553c1701d59090930be4b500fb0c62d9f860186f9a62f5b36be13f
spring-ai-autoconfigure-mcp-server-webflux-2.1.0-RC1-sources.jar
f06b070b6c8cccac936578153d060b017f6f965eb26eec81b757edcb627c03f6
```

## Decision, coexistence and remaining risk

Adopt option A in [ADR 0014](decisions/0014-modern-protocol-compatibility-proposal.md):
retain pinned product dependencies. No modern server/client/starter combination
has passed the required gate. The preview's server viability is limited to the
explicit direct servlet experiment. Its lack of modern client blocks S7; missing
WebFlux registration and header validation gaps block treating it as a ready S3
replacement. No observed Reactor/Jackson binary error is claimed as a blocker.

Preserve the legacy endpoint/profiles and owner/session controls. The preview
modern servlet is modern-only: GET/DELETE and legacy initialization fail; its
legacy API can run separately, but this does not demonstrate a dual-era endpoint.
Do not route failed calls into a second protocol era. When dependencies mature,
prefer a supported starter/BOM; otherwise a direct transport binding needs a
separate design and regression gate. A separate opt-in deployment could preserve
the same endpoint path per deployment, but no new deployment, servlet migration,
extra endpoint or simultaneous dual-era routing is selected by S0.

No official conformance suite was executed locally. Upstream reported conformance
is not gateway evidence. Modern OAuth/UC-01/audit/revocation, proxy flushing,
slow-consumer saturation, bounded schema reference/composition handling, backend
cancellation propagation, legacy-client 2.0.0 interoperability with a migrated
server, and native modern streaming/fallback remain unverified. The physical
disconnect result covers a reactive fixture emitting progress, not arbitrary
blocking backend code or a silent stream before its next failed write.

Reopen S0's dependency gate when an official modern Java client, supported modern
Spring binding and required header handling are published. Re-run these probes,
replace gap-characterization expectations with rejection assertions, then execute
the complete secured-gateway MP/normative matrix. Until then S1/S2/S4 and registry
domain design can progress independently on the pinned stack. **The next bounded
product task is S1 only**, following its requirements and acceptance evidence.
