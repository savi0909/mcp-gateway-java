# Verified dependency and API baseline

Initial source/API inspection: 2026-10-07. Later foundation and UC-01 protocol
verification is recorded in [WORKLOG](../WORKLOG.md) and the acceptance trackers;
the October 28 regression baseline is 143 unit/startup and 38 integration tests
under `target/roadmap-review-20261028`. This run tests MCP `2025-11-25`;
[modern compatibility planning](GATEWAY_SPRINT_ROADMAP.md) selects no upgrade.
The stack below is unchanged. Dated initial observations are historical evidence.

| Component | Version / evidence |
| --- | --- |
| Java target | 21; local JDK 21.0.9 |
| Spring Boot parent and starter family | 4.0.8 |
| Spring AI BOM and MCP WebFlux starter | 2.0.1 |
| BOM-managed MCP SDK (`mcp`, `mcp-core`, `mcp-json-jackson3`) | 2.0.0 |
| Maven distribution | 3.9.11 |
| Maven Wrapper | 3.3.4, binary wrapper JAR committed as a project file |
| Surefire / Failsafe | 3.5.6, Boot-managed |

`spring-ai-model` is a transitive library of the MCP starter; it is not a model-provider starter and requires no provider credentials here. SDK JSON support is Jackson 3 in this resolved set. Inspect Boot's application JSON APIs before introducing imports copied from a Jackson 2 example.

## APIs inspected in the resolved source artifacts

- `McpServerAutoConfiguration` receives `ObjectProvider<List<McpServerFeatures.AsyncToolSpecification>>` for ASYNC tool registration. A bean supplying that list is the intended file-driven registration point.
- SDK `AsyncToolSpecification` contains an `McpSchema.Tool` and a `BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>>`. Its builder offers `tool(...)`, `callHandler(...)`, and `build()`.
- `CallToolResult.Builder` supports `structuredContent(Object)` and `isError(Boolean)`. Construct success/error results using resolved SDK types; execution tests must prove advertised-schema behavior.
- `HttpClientStreamableHttpTransport.builder(String baseUri)` supports `.endpoint(String)` and `.build()`. Configure the origin and endpoint separately rather than guessing URL concatenation.
- `McpClient.async(McpClientTransport)` builds an async client with configurable `requestTimeout(Duration)`. `McpAsyncClient` exposes reactive `initialize()`, `listTools()`, `callTool(CallToolRequest)` and `closeGracefully()`. Integration/smoke code performs initialization and bounded cleanup; real Boot/SDK tests now verify protocol `2025-11-25`.

These signatures were read from matching Maven Central source JARs. Subsequent
compilation and protocol tests verify their actual use; new API changes still
require inspection against the pinned resolved binaries.

## Starter properties inspected

The 2.0.1 configuration metadata confirms `enabled`, `name`, `version`, `type`, `protocol`, `request-timeout`, `annotation-scanner.enabled`, `capabilities.{tool,resource,prompt,completion}`, the three change-notification flags, and `streamable-http.mcp-endpoint`, under `spring.ai.mcp.server`.

`tool-callback-converter` is enforced by a `ConditionalOnProperty` in `ToolCallbackConverterAutoConfiguration` even though it is absent from the inspected configuration metadata list. It remains explicitly false alongside the implemented low-level registration.

The WebFlux streamable transport source uses `McpServerStreamableHttpProperties.getMcpEndpoint()` to configure its transport route. Keep the fixed endpoint `/worker-coordinator/mcp`. Application-specific `gateway.*` keys are implemented by GatewayProperties; they are not starter properties.

## Reproduce and continue

```powershell
.\mvnw.cmd -version
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd -B -ntp dependency:tree '-Dincludes=org.springframework.ai:*,io.modelcontextprotocol.sdk:*,org.springframework.boot:*'
```

Inspect the `2.0.1` source artifacts `spring-ai-autoconfigure-mcp-server-common` and `spring-ai-autoconfigure-mcp-server-webflux`, and the `2.0.0` source artifact `mcp-core`, from Maven Central. Resolved binary metadata lives at `META-INF/spring-configuration-metadata.json` inside the auto-configuration JARs. The initial inspection downloaded copies into ignored `target/`; those files are not required for a build.

Wrapper properties include SHA-256 checksums for the Maven distribution and wrapper JAR, computed from Maven Central bytes. The existing local Maven distribution was reused; a fresh-cache checksum/download path has not been exercised here.

Stage 1B inspection: resolved Reactor Netty HTTP/core 1.3.7, Spring WebFlux 7.0.9 and Jackson 3.1.5. Read their matching source artifacts, including `HttpClient.disableRetry(boolean)`, `followRedirect(boolean)`, `responseTimeout(Duration)`, `ConnectionProvider` pool/queue/acquire bounds, WebClient's response release behavior, JSON Pointer compilation and integral-number parsing. The executor explicitly disables connector retries and redirects, uses a total Reactor deadline, and joins raw DataBuffers with a byte limit. WebClient body release can drain an unconsumed error response; the executor subscribes and cancels that body before release.

User-authorized application retries are described in ADR 0002. The historical
local live demo negotiated `2025-11-25`, discovered `create_sample_payment` and
completed two invocations. Subsequent independent connector/error/catalog and
response-loss tests now supply bounded failure/retry evidence. Live successful
calls alone do not prove that behavior. The SDK's three-argument
`CallToolRequest(name, arguments, meta)` is used; its older two-argument constructor
is deprecated in 2.0.0. UC-01 authorized reads use a separate single-attempt path.

## Official references

- [Spring AI compatibility and BOM](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Streamable HTTP starter properties](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html)
- [MCP SDK 2.0.0 source artifact](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/sdk/mcp-core/2.0.0/)
- [Spring AI common auto-configuration source artifact](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-autoconfigure-mcp-server-common/2.0.1/)
- [Reactor Netty 1.3.7 HTTP client API](https://projectreactor.io/docs/netty/1.3.7/api/reactor/netty/http/client/HttpClient.html)
- [Spring Framework DataBufferUtils byte-bound join](https://docs.spring.io/spring-framework/docs/7.0.x/javadoc-api/org/springframework/core/io/buffer/DataBufferUtils.html)
- [UUIDv7 request-key format, RFC 9562 section 5.7](https://www.rfc-editor.org/rfc/rfc9562.html#section-5.7)
