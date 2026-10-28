# MCP 2026-07-28 selected-scope requirement inventory

S0 investigation, 2026-10-28. This inventory refines the grouped
[MP acceptance rows](MCP_PROTOCOL_ACCEPTANCE.md). **Every product requirement
below remains Pending.** SDK experiment observations are linked separately in
[the compatibility findings](S0_COMPATIBILITY_FINDINGS.md); they do not verify
the secured gateway. Normative requirements are paraphrased, with their source
sections identified below. Recheck this inventory against the dated schema and
specification when selecting an implementation SDK.

## Roles, capability scope and source coverage

`S` is the gateway's inbound server role; `C` is its future outbound native MCP
client role; `B` means both. Product support remains inbound `2025-11-25` on the
pinned runtime, REST outbound, and no native MCP outbound. The investigated
target is inbound and outbound `2026-07-28`, tools over Streamable HTTP, ordinary
internal reads only. Preserve the tested legacy inbound contract. Legacy native
upstreams require an explicit S7 routing/capability contract before enablement;
HTTP+SSE/stdio are not selected. There is no automatic business-call replay or
fallback on uncertain outcomes.

Modern tools/list and tools/call, server/discover, JSON results, request-scoped SSE,
cancellation, private cache metadata, schema validation and authenticated requests
are in scope. No resources, prompts, completion, change subscriptions, Tasks,
sampling, roots, elicitation, MRTR workflow or logging capability is selected.
The optional custom tool-parameter header annotation is not published by our
REST catalog. **The outbound modern client still has mandatory handling duties
for upstream annotations**, so missing SEP-2243 support cannot be dismissed as an
optional-client feature. Upstream tools outside the selected schema/capability
contract must fail publication rather than silently lose fields.

The following are the normative source sections reviewed. The inspection script
downloads these exact revision pages, source artifacts and a SHA-256 manifest
under ignored `target/s0-inspection`; no moving draft is used as the contract.

| Source | Sections / normative scope |
| --- | --- |
| [Base](https://modelcontextprotocol.io/specification/2026-07-28/basic/index) | Messages, errors, statelessness, JSON Schema, metadata, icon and trace conditions |
| [Versioning](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning) | Per-request versions, unsupported versions, extension negotiation, compatibility |
| [HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http) | Endpoint/security, POST, results/SSE, cancellation, metadata/custom headers, validation, compatibility |
| [Discover](https://modelcontextprotocol.io/specification/2026-07-28/server/discover) | Mandatory discovery and result fields |
| [Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools) | Capabilities, list/call, schema, results, custom headers, access/input/output controls |
| [Caching](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching) | Hints, keys, context boundaries, page consistency, access controls |
| [Pagination](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/pagination) | Opaque cursors, server-controlled page sizes |
| [Cancellation](https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns/cancellation), [progress](https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns/progress) | HTTP disconnect, cancellation races, token/progress rules |
| [Authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/index) | Resource server/client requirements, scopes, issuer response checks, resource indicators, tokens |
| [AS discovery](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery) | Protected-resource metadata, challenge handling, issuer endpoint ordering/validation |
| [Registration](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration) | Issuer-bound credentials, registration choice, conditional metadata/DCR requirements |
| [OAuth security](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations) | TLS, PKCE, storage, redirect, audience, separate downstream credentials, conditional proxy consent |
| [Schema](https://modelcontextprotocol.io/specification/2026-07-28/schema) | Required fields/types for each selected message/result/content type; field-level wire validation remains required |

The schema is an additional executable validation input, not a replacement for
the prose requirements. S3/S7 must validate required/optional field shapes for
`JSONRPCRequest`, `JSONRPCResponse`, `JSONRPCErrorResponse`, `DiscoverResult`,
`ListToolsResult`, `CallToolRequest`, `CallToolResult`, `Tool`, `Content`,
`ClientCapabilities`, `ServerCapabilities` and the selected typed errors.
Unselected capability-specific types are excluded until that capability is
selected. Imported OAuth/RFC requirements remain obligations of the selected
implementation/provider; the inventory does not certify OAuth compliance.

## Requirement-to-evidence ledger

The last column names **planned gateway tests**, not tests claimed to exist.
They provide a concrete S3/S5/S7 implementation checklist. Each name is scoped
by the ID, so multiple cases in a named suite remain individually traceable.
Existing experimental methods and their exact outcomes are in the findings.

| ID | Role | Obligation / source section | MP row | Planned gateway test |
| --- | --- | --- | --- | --- |
| B01 | B | Implement the base protocol, versioning and selected transport (Base) | MP-02 | ModernProtocolIT.baseContract |
| B02 | B | Use JSON-RPC 2.0 message shapes (Base/Messages) | MP-07 | ModernProtocolIT.envelopes |
| B03 | C | Give requests string/integer, non-null identifiers (Base/Requests) | MP-07 | NativeClientIT.requestIds |
| B04 | C | Do not reuse an identifier while its earlier request is outstanding (Base/Requests) | MP-07 | NativeClientIT.concurrentIds |
| B05 | S | Echo the request identifier in result responses (Base/Responses) | MP-07 | ModernProtocolIT.resultIds |
| B06 | S | Include a result object on success and its required resultType (Base/Responses) | MP-07 | ModernProtocolIT.resultTypes |
| B07 | B | Recognize only core plus mutually advertised extension result types (Base/Responses) | MP-07 | NativeClientIT.unknownResultType |
| B08 | C | Interpret absent resultType as complete for legacy responses (Base/Responses) | MP-05 | NativeClientIT.legacyResult |
| B09 | S | Echo the request ID in errors except when unreadable in malformed input (Base/Errors) | MP-07 | ModernProtocolIT.errorIds |
| B10 | B | Error envelopes carry integer code and message, using defined meanings (Base/Errors) | MP-07 | ModernProtocolIT.errorShapes |
| B11 | B | Do not allocate new codes in grandfathered implementation range or invent meanings for peers' codes (Base/Error allocation) | MP-07 | ModernProtocolIT.errorAllocation |
| B12 | B | Emit only specified MCP-reserved codes with their defined semantics (Base/Error allocation) | MP-07 | ModernProtocolIT.reservedErrors |
| B13 | S | Do not emit retired -32002/-32042 codes in modern responses (Base/Error allocation) | MP-07 | ModernProtocolIT.retiredErrors |
| B14 | B | Notifications have no ID and receive no JSON-RPC response (Base/Notifications) | MP-07 | ModernProtocolIT.notificationShapes |
| B15 | S | Establish version, client capabilities and identity per request, independently of connections (Base/Statelessness) | MP-04 | ModernIdentityIT.independentRequests |
| B16 | B | Reference cross-request application state using explicit handles (Base/Statelessness) | MP-04 | ModernIdentityIT.noImplicitState |
| B17 | B | Default absent schema dialect to 2020-12 and support that dialect (Base/JSON Schema) | MP-11 | NativeSchemaIT.defaultDialect |
| B18 | B | Validate against declared/default dialect; reject unsupported dialects gracefully (Base/JSON Schema) | MP-11 | NativeSchemaIT.dialectRejection |
| B19 | B | Require valid schemas in their dialect (Base/Schema validation) | MP-11 | NativeSchemaIT.invalidSchema |
| B20 | B | Never fetch arbitrary external refs automatically; remote resolution stays disabled by default (Base/Ref resolution) | MP-11 | NativeSchemaIT.noExternalFetch |
| B21 | B | Do not infer semantics for unknown metadata keys (Base/Meta) | MP-07 | ModernProtocolIT.unknownMeta |
| B22 | B | Use legal dotted-prefix/key syntax for emitted metadata (Base/Meta) | MP-07 | ModernProtocolIT.metaNames |
| B23 | C | Include protocolVersion and clientCapabilities metadata on every request (Base/Per-request fields) | MP-02 | NativeClientIT.requestMeta |
| B24 | S | Reject missing required metadata with -32602 and HTTP 400 (Base/Per-request fields) | MP-02 | ModernProtocolIT.missingMeta |
| B25 | S | Never require undeclared client capability; return -32021, requiredCapabilities and HTTP 400 when needed (Base/Per-request fields) | MP-02 | ModernProtocolIT.missingCapability |
| B26 | B | Validate selected wire fields/types against dated schema, including typed errors (Schema) | MP-14 | ModernSchemaIT.selectedWireTypes |
| V01 | S | Reject unsupported versions with -32022 listing supported versions (Versioning) | MP-02 | ModernProtocolIT.unsupportedVersion |
| V02 | S | Implement server/discover with actual versions/capabilities/identity fields (Versioning/Discover) | MP-02 | ModernProtocolIT.discovery |
| V03 | B | Prefix extension identifiers according to metadata rules (Versioning/Extensions) | MP-02 | ModernProtocolIT.extensionNames |
| V04 | B | Unsupported extensions fall back to core behavior or fail explicitly (Versioning/Extensions) | MP-02 | ModernProtocolIT.unsupportedExtension |
| H01 | S | Expose one MCP HTTP path accepting POST (HTTP/Endpoint) | MP-04 | ModernHttpIT.endpoint |
| H02 | S | Check Origin on every incoming connection; invalid present values receive 403 (HTTP/Security) | MP-09 | ModernHttpIT.origin |
| H03 | C | Send each message as its own POST (HTTP/Sending) | MP-03 | NativeClientIT.singlePost |
| H04 | C | Advertise acceptance of both JSON and SSE responses (HTTP/Sending) | MP-08 | NativeClientIT.acceptTypes |
| H05 | C | Include the required metadata headers on each request (HTTP/Sending) | MP-03 | NativeClientIT.routingHeaders |
| H06 | C | Send a single request/notification body, never a JSON-RPC response body (HTTP/Sending) | MP-03 | NativeClientIT.bodyShapes |
| H07 | S | Accepted notifications return empty HTTP 202; rejected ones return HTTP errors (HTTP/Sending) | MP-07 | ModernHttpIT.notifications |
| H08 | S | Request responses use JSON or SSE content type (HTTP/Sending) | MP-08 | ModernHttpIT.responseTypes |
| H09 | C | Handle both JSON and SSE response forms (HTTP/Sending) | MP-08 | NativeClientIT.responseTypes |
| H10 | S | Stream notifications concern only the originating request; never send independent requests on SSE (HTTP/Receiving) | MP-08 | ModernHttpIT.requestScopedStream |
| H11 | S | Treat response-stream closure as cancellation; do not send further messages on the closed stream (HTTP/Cancellation) | MP-08 | ModernHttpIT.disconnect |
| H12 | C | A reissued request after stream loss has a fresh ID; no transport resumption/replay (HTTP/Resumability removal) | MP-13 | NativeClientIT.lostStream |
| H13 | C | Include the protocol header on every request (HTTP/Protocol version) | MP-03 | NativeClientIT.versionHeader |
| H14 | S | Compare version header to body metadata; reject mismatch with HTTP 400/-32020 (HTTP/Protocol version) | MP-03 | ModernHttpIT.versionMismatch |
| H15 | S | Unsupported version produces HTTP 400/-32022 with supported versions (HTTP/Protocol version) | MP-02 | ModernHttpIT.unsupportedVersion |
| H16 | S | Unknown RPC method produces HTTP 404/-32601 (HTTP/Protocol version) | MP-02 | ModernHttpIT.unknownMethod |
| H17 | S | Missing protocol header is rejected when old headerless clients are unsupported (HTTP/Protocol version) | MP-03 | ModernHttpIT.missingVersion |
| H18 | C | Send method header always and name header for tools/call (HTTP/Standard headers) | MP-03 | NativeClientIT.methodAndName |
| H19 | C | Encode names unsafe for plain ASCII headers in the prescribed sentinel format (HTTP/Standard headers) | MP-03 | NativeClientIT.encodedNames |
| H20 | C | Recognize upstream x-mcp-header annotations and mirror non-null argument values (HTTP/Custom headers) | MP-03 | NativeClientIT.parameterHeaders |
| H21 | B | Validate nonempty token-syntax custom header names without controls; uniqueness is case-insensitive (HTTP/Custom constraints) | MP-11 | NativeSchemaIT.headerNames |
| H22 | B | Annotated values allow string/boolean/safe integer only, not number (HTTP/Custom constraints) | MP-11 | NativeSchemaIT.headerTypes |
| H23 | B | Annotated properties must be statically reachable through properties only, not arrays/composition/dynamic refs (HTTP/Custom constraints) | MP-11 | NativeSchemaIT.headerReachability |
| H24 | C | Exclude invalid annotated tools from discovered results (HTTP/Custom constraints) | MP-11 | NativeSchemaIT.excludeInvalidTool |
| H25 | C | Serialize header parameter values using the specified scalar encoding (HTTP/Value encoding) | MP-03 | NativeClientIT.scalarEncoding |
| H26 | C | Encode unsafe UTF-8/whitespace and sentinel-looking ASCII using exact lowercase base64 sentinel markers (HTTP/Value encoding) | MP-03 | NativeClientIT.sentinelEncoding |
| H27 | S | Decode encoded names/parameters before body comparison (HTTP/Value encoding) | MP-03 | ModernHttpIT.decodeHeaders |
| H28 | S | When acting as an intermediary, forward and otherwise ignore unrecognized parameter headers (HTTP/Custom header behavior) | MP-03 | ModernHttpIT.headerRoles |
| H29 | S | Reject recognized parameter headers containing invalid characters (HTTP/Custom header behavior) | MP-03 | ModernHttpIT.invalidParameterHeader |
| H30 | S | Body-processing server checks encoded method/name/parameter values against body (HTTP/Server validation) | MP-03 | ModernHttpIT.headerMismatch |
| H31 | B | Omit parameter header for null/absent argument; server must not require it (HTTP/Null handling) | MP-03 | ModernHttpIT.nullParameter |
| H32 | S | Require parameter header when a non-null annotated argument is supplied (HTTP/Server validation) | MP-03 | ModernHttpIT.missingParameterHeader |
| H33 | B | Match HTTP header names case-insensitively (HTTP/Server validation) | MP-03 | ModernHttpIT.headerCase |
| H34 | S | Header validation failures produce HTTP 400 and -32020 (HTTP/Server validation) | MP-03 | ModernHttpIT.headerErrors |
| T01 | S | Advertise tools capability when serving tools (Tools/Capabilities) | MP-02 | ModernProtocolIT.toolCapability |
| T02 | S | Answer tools/list when tools capability is advertised (Tools/Listing) | MP-06 | ModernCatalogIT.list |
| T03 | S | Listings cannot depend on connection or earlier requests; authorization-dependent filtering is allowed (Tools/Listing) | MP-06 | ModernCatalogIT.callerIsolation |
| T04 | S | Advertised inputSchema must be a JSON Schema object, never null (Tools/Schema) | MP-11 | NativeSchemaIT.inputSchema |
| T05 | C | Treat tool annotation hints from untrusted servers as untrusted (Tools/Annotations) | MP-11 | NativeSchemaIT.untrustedAnnotations |
| T06 | S | Structured results conform to advertised outputSchema when present (Tools/Output schema) | MP-07 | ModernToolIT.outputSchema |
| T07 | S | Validate every tool input (Tools/Security) | MP-07 | ModernToolIT.invalidArguments |
| T08 | S | Apply tool access controls (Tools/Security) | MP-09 | ModernIdentityIT.deniedCall |
| T09 | S | Rate-limit tool invocations (Tools/Security) | MP-14 | ModernLimitsIT.rateLimit |
| T10 | S | Sanitize tool outputs (Tools/Security) | MP-07 | ModernToolIT.sanitizedOutput |
| K01 | S | Include ttlMs/cacheScope on complete discovery and list results (Caching/Cacheable results) | MP-06 | ModernCatalogIT.cacheFields |
| K02 | C | Never reuse cache across a different method or result-affecting parameters (Caching/Key) | MP-06 | NativeCacheIT.keys |
| K03 | C | Do not cache requestState/inputResponses retries (Caching/Key) | MP-06 | NativeCacheIT.noRetryCache |
| K04 | S | ttlMs is a nonnegative integer (Caching/TTL) | MP-06 | ModernCatalogIT.ttl |
| K05 | C | If polling is selected, apply jitter and backoff (Caching/TTL) | MP-06 | NativeCacheIT.pollBounds |
| K06 | B | Private caches never cross authorization contexts (Caching/Scope) | MP-06 | NativeCacheIT.contextIsolation |
| K07 | S | Keep cacheScope identical across pages of a listing (Caching/Pagination) | MP-06 | ModernCatalogIT.pageScope |
| K08 | S | Public caches may escape auth context; select scope accordingly and enforce primitive authorization independently (Caching/Security) | MP-06 | ModernCatalogIT.directAuthorization |
| P01 | C | Do not assume a fixed page size; treat cursors as opaque (Pagination) | MP-06 | NativeCacheIT.pagination |
| X01 | S | HTTP disconnect cancels the request (Cancellation/Transport) | MP-13 | ModernHttpIT.disconnect |
| X02 | B | Handle cancellation/completion races gracefully (Cancellation/Timing) | MP-13 | ModernHttpIT.cancelRace |
| X03 | C | Progress token is string/integer and unique across outstanding requests (Progress) | MP-08 | NativeClientIT.progressTokens |
| X04 | S | Progress values strictly increase (Progress) | MP-08 | ModernHttpIT.progressOrder |
| X05 | S | Progress references only a supplied active token and stops after completion (Progress) | MP-08 | ModernHttpIT.progressLifetime |
| A01 | S | Implement OAuth protected-resource metadata with at least one authorization server (Authorization/AS discovery) | MP-09 | ModernOAuthIT.resourceMetadata |
| A02 | S | Provide resource-metadata discovery by challenge or well-known URL (AS discovery) | MP-09 | ModernOAuthIT.challenge |
| A03 | C | Parse 401 challenges; support both resource-metadata discovery methods, preferring challenge URL then endpoint/root well-known order (AS discovery) | MP-09 | NativeOAuthIT.resourceDiscovery |
| A04 | C | Support OAuth and OIDC issuer metadata discovery, with specified path-insertion/appending order (AS discovery) | MP-09 | NativeOAuthIT.issuerDiscovery |
| A05 | C | Validate discovered issuer exactly against the intended issuer and reject mismatching metadata (AS discovery) | MP-09 | NativeOAuthIT.issuerMetadata |
| A06 | C | Obtain client ID by a supported registration mechanism before authorization (Authorization/Registration) | MP-09 | NativeOAuthIT.registration |
| A07 | C | Treat challenge scopes as authoritative; do not infer subset/superset relationships to advertised scopes (Authorization/Scope) | MP-09 | NativeOAuthIT.scopeChallenge |
| A08 | C | Record validated issuer with the PKCE/state authorization request record (Authorization/Response validation) | MP-09 | NativeOAuthIT.expectedIssuer |
| A09 | C | Validate response iss per RFC9207 before code redemption, including presence rules for advertised support (Authorization/Response validation) | MP-09 | NativeOAuthIT.responseIssuer |
| A10 | C | Compare decoded response issuer exactly without URI normalization (Authorization/Response validation) | MP-09 | NativeOAuthIT.issuerNormalization |
| A11 | C | On issuer mismatch, do not act on/display error/error_description/error_uri (Authorization/Response validation) | MP-09 | NativeOAuthIT.errorIssuer |
| A12 | C | Send canonical intended MCP resource indicator in both authorization and token requests, regardless of AS support (Authorization/Resource) | MP-09 | NativeOAuthIT.resourceIndicator |
| A13 | C | Send authorization in every HTTP request header, never query strings (Authorization/Token usage) | MP-09 | NativeOAuthIT.tokenPlacement |
| A14 | S | Validate access token before processing; require intended audience/resource (Authorization/Token usage) | MP-09 | ModernOAuthIT.audience |
| A15 | S | Invalid/expired access token returns 401 using required OAuth error handling (Authorization/Token usage) | MP-09 | ModernOAuthIT.invalidToken |
| A16 | C | Send only tokens issued for that MCP resource by its authorization server (Authorization/Token usage) | MP-09 | NativeOAuthIT.tokenAudience |
| A17 | S | Do not accept unrelated tokens or transit inbound bearer tokens downstream (Authorization/Token usage) | MP-09 | ModernOAuthIT.noPassthrough |
| A18 | C | Protect refresh tokens in transit/storage; do not assume one will be issued (Authorization/Refresh) | MP-09 | NativeOAuthIT.refreshTokens |
| A19 | S | Use correct authorization HTTP statuses: 401 invalid authentication, 403 insufficient permission (Authorization/Errors) | MP-09 | ModernOAuthIT.errorStatuses |
| A20 | S | Account for scope hierarchies when determining sufficient permission (Authorization/Scope) | MP-09 | ModernOAuthIT.scopeHierarchy |
| A21 | C | Persist credentials/registration state keyed by issuing AS issuer (Registration/AS binding) | MP-09 | NativeOAuthIT.credentialIssuer |
| A22 | C | On AS change never reuse old credentials; register for the new AS (Registration/AS binding) | MP-09 | NativeOAuthIT.changedIssuer |
| A23 | B | Secure token storage and follow OAuth communication security requirements (OAuth security) | MP-09 | ModernOAuthIT.tokenStorage |
| A24 | C | Use HTTPS authorization endpoints and registered HTTPS/localhost redirect URIs (OAuth security) | MP-09 | NativeOAuthIT.secureEndpoints |
| A25 | C | Implement PKCE and verify AS support before beginning authorization (OAuth security/PKCE) | MP-09 | NativeOAuthIT.pkceRequired |
| A26 | C | Use S256 when capable; absent challenge-method metadata in OAuth/OIDC causes refusal (OAuth security/PKCE) | MP-09 | NativeOAuthIT.pkceMetadata |
| A27 | C | Register redirect URIs with the issuer (OAuth security/Redirect) | MP-09 | NativeOAuthIT.redirectRegistration |
| A28 | S | Preserve no-protected-data guarantee on token rejection and separate upstream credentials (OAuth security/Privilege) | MP-09 | ModernOAuthIT.denialCounters |

## Conditional obligations and exclusions

These clauses are not silently waived. The condition determines whether they
apply to an enabled runtime. Do not advertise the feature until its tests exist.

| ID | Condition / obligation | Row and planned test |
| --- | --- | --- |
| Q01 | If gateway trace metadata is emitted, use W3C trace/baggage formats (Base/Meta) | MP-07; ModernTraceIT.formats |
| Q02 | If icons are rendered, support PNG/JPEG, treat metadata/bytes as untrusted, enforce HTTPS/data same-origin URI and redirect constraints, fetch without credentials, bound resources, and validate image type/content (Base/Icons) | MP-11; NativeContentIT.icons |
| Q03 | If dynamic client registration is used, set appropriate application_type and handle redirect-constraint failures (Registration/DCR) | MP-09; NativeOAuthIT.dcr |
| Q04 | If client ID metadata documents are used, follow the referenced draft, serve valid required JSON fields over an HTTPS client_id URL with path, and match client_id exactly (Registration/Metadata) | MP-09; NativeOAuthIT.metadataDocument |
| Q05 | If acting as static-client-ID proxy for dynamically registered clients to third parties, obtain per-client user consent first (OAuth security/Confused deputy) | MP-09; ProviderOAuthIT.consent; third-party execution disabled |
| Q06 | If MRTR is enabled, use input_required only on permitted RPCs; inputRequests keys are unique and values are permitted typed requests (MRTR) | MP-16; ModernMrtrIT.requestShapes |
| Q07 | If MRTR is enabled, include inputRequests or requestState; emit only client-declared input capabilities; never assume fulfilment/retry (MRTR) | MP-16; ModernMrtrIT.capabilities |
| Q08 | If state influences authority/business behavior, protect integrity and reject tampering; enforce server-side one-use where needed (MRTR) | MP-16; ModernMrtrIT.stateIntegrity |
| Q09 | On MRTR retry construct requested input responses, echo exact opaque state only when supplied, never parse it, use fresh ID, and isolate from parallel requests (MRTR) | MP-16; NativeMrtrIT.retry |
| Q10 | If subscriptions are enabled, tag notifications with subscriptionId and enforce all acknowledged-type, isolation and teardown clauses (Base/Meta; Subscriptions) | MP-15; ModernSubscriptionsIT.lifecycle |
| Q11 | Server cancellation notifications are only for subscriptions/listen teardown; do not emit them for ordinary tool requests (Cancellation) | MP-15; ModernSubscriptionsIT.cancel |
| Q12 | If logging is enabled, obey per-request logLevel and capability restrictions; never send messages without the requested logLevel (Logging) | MP-15; ModernLoggingIT.requestLevel |
| Q13 | Authorization server must implement OAuth 2.1, supply OAuth/OIDC metadata, advertise response-iss support if emitting it, and include PKCE metadata for OIDC (Authorization/OAuth security) | MP-09; IdentityProviderIT.metadata; provider integration duty, gateway is not an AS |
| Q14 | AS must rotate public-client refresh tokens, require HTTPS endpoints/exact registered redirect matching, and prevent untrusted redirection (OAuth security) | MP-09; IdentityProviderIT.security; provider duty |
| Q15 | AS implementing client ID metadata must validate exact IDs, redirects, JSON/required fields and draft security; display redirect hostname clearly (Registration/OAuth security) | MP-09; IdentityProviderIT.clientMetadata; provider duty |
| Q16 | stdio cancellation notification and fallback rules, alternate transports and deprecated HTTP+SSE | Not selected; separate transport decision/test matrix required |
| Q17 | Resources, prompts, completion, roots, sampling, elicitation and Tasks extension field/behavior obligations | MP-15/16; not selected; do not advertise/translate them |

## Additional gateway guarantees

The spec's caching freshness/stale-response permissions do not grant execution
authority. Gateway policy/audit/revocation checks remain mandatory on every call.
The product requires generation/authorization-bound cursors even though the
protocol does not promise cross-page snapshots. MP-03 additionally requires
unambiguous duplicate-header rejection; the SDK preview experiment demonstrates
why that integration boundary needs an explicit test. Retry/fallback after an
uncertain business outcome remains disabled unless the action has a separately
proven safe replay contract and fresh admission.

These protocol duties do not discharge the separate full identity/catalog
requirements. S3/S7 must add actual secured-gateway tests for this ledger, run
applicable official conformance, and audit field-level schema coverage before
any MP row becomes Verified.
