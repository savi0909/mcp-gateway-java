# Milestone 1 acceptance evidence

`docs/FUNCTIONAL_SPEC.md` section 7 is authoritative. Mark a row `verified` only after an actual automated run demonstrates all parts. Use `partial` for limited scaffold/unit evidence and `pending` for missing tests. Record commands/results in `WORKLOG.md`.

This page records completed foundation evidence only. The selected next
[UC-01 slice](UC01_ADMISSION_PLAN.md) has no new functional evidence.
[Identity acceptance](IDENTITY_ACCEPTANCE.md) separately tracks v1.4 IA-AC-01
through IA-AC-32, all pending. Existing Verified rows below do not satisfy the
new identity baseline or its full first-release gate.

The owner explicitly resumed independent tests on 2026-10-07. Full Wrapper
verification passed with **121 unit/startup tests and 20 integration tests**, zero
failures/errors/skips, using `-Dgateway.build-directory=target/foundation` to
preserve the existing Windows demo JAR. ADRs 0002–0004 supersede original
single-attempt/single-tool/demo-backend constraints. All evidence below is
independent mock/real SDK evidence unless explicitly labeled historical live.

| ID | Evidence required | State | Current evidence / next test |
| --- | --- | --- | --- |
| AC-01 | Wrapper executable packaging and Boot startup without model credentials | Verified | Full executable packaging; ScaffoldStartupTest and random-port Boot/SDK/process smoke |
| AC-02 | SDK negotiation and exact file-defined single-tool discovery | Verified | CatalogProtocolIT plus standalone SDK worker discovery; file schemas/description/hints; protocol 2025-11-25 |
| AC-03 | Zero allocation on startup/discovery; no private discovery metadata | Verified | CatalogProtocolIT/CoordinatorToolsIT counter and public metadata assertions; backend-outage discovery; standalone stats remain 0 |
| AC-04 | Two calls, two requests, exact backend IDs, matching text/structured content | Verified | CatalogProtocolIT exact IDs/output/requests; separate-process SDK --count=2 yields exactly 2 mock allocations |
| AC-05 | Exact method/path/body/auth; GET without body | Verified | RestBindingExecutorTest, CoordinatorToolsIT all nine mappings/separate tokens, CatalogProtocolIT changed GET binding |
| AC-06 | Unknown tool/invalid arguments make zero allocation requests | Verified | CoordinatorToolsIT with real SDK and counters; gateway validation supplies sanitized INVALID_ARGUMENTS; unknown tools remain SDK-owned |
| AC-07 | Sanitized HTTP errors, no followed redirects, no canary leakage | Verified | RestBindingExecutorTest status/stalled-body/canary matrix; CatalogProtocolIT/CoordinatorToolsIT real SDK errors, no structured errors or followed redirects |
| AC-08 | Bounded disconnect/deadline, unknown outcome; ADR 0002 application retries supersede original no-retry policy | Verified | Executor and ResponseLossIT counters: stable attempts/keys within total deadline; logical commit persists; direct disposal and physical SDK HTTP reset stop local attempts |
| AC-09 | Invalid/oversized replies rejected; nested pointer and large integer preserved | Verified | Executor invalid-body/type/size matrix, CatalogProtocolIT nested exact integer, CoordinatorToolsIT precise epochs/projections |
| AC-10 | Duplicate coordinator IDs returned unchanged | Verified | Executor and CatalogProtocolIT repeat the same backend-issued string; matching output, no substituted ID |
| AC-11 | Fresh-context catalog edits work; running edit does not reload | Verified | CatalogProtocolIT retains running name after edit; fresh context gets new name/description/GET path/nested pointer |
| AC-12 | Invalid/missing/unsupported/duplicate catalog prevents startup | Verified | FileCatalogLoaderTest validation matrix; CatalogProtocolIT failed Boot contexts for missing/malformed/duplicate JSON, unsupported version/schema/native binding/cardinality |
| AC-13 | Origin allow/deny/absence enforced before REST | Verified | CoordinatorToolsIT ordinary clients, both allowed Origins and rejected Origin; SecuredGatewayIT denial with valid credential; backend counters |

The baseline is complete under the documented user-directed exceptions. Tests
never allocate live IDs. The original `/workers/ids` contract is demonstrated by
an independent mock, not claimed as the real coordinator API. Historical local
payment success is not live resilience/security evidence. No production or hosted
CI run occurred. Graceful SDK closure alone is not verified cancellation; physical
HTTP reset/local disposal and total deadlines have explicit evidence.

## User-directed worker API extension (ADR 0004)

| Evidence | State | Actual result / next action |
| --- | --- | --- |
| Compile/package multi-tool gateway and tests | Verified | Full Wrapper verification, 141 tests, executable JAR in target/foundation |
| SDK discovery of nine worker/namespace tools plus payment | Verified | CoordinatorToolsIT discovers ten names without backend calls |
| All coordinator mappings, response fields, epochs and private backend routing | Verified | CoordinatorToolsIT invokes all nine mappings against independent mocks, separate payment origin/token, exact large epoch |
| Unsupported catalogs, schemas, duplicate names and immutable metadata | Verified | FileCatalogLoaderTest and invalid/lifecycle Boot protocol contexts |
| Argument/Origin enforcement and sanitized worker failures | Verified | CoordinatorToolsIT and SecuredGatewayIT counters/error assertions |

The extension supersedes the original single-tool boundary for catalog version 3.
Versions 1 and 2 retain their original cardinality. Worker mutations are
mock-verified; no new live registration/lease/payment mutations were performed.

## Selected security/failure extension (ADR 0006)

| Evidence | State | Actual result |
| --- | --- | --- |
| JWT signature/issuer/audience/expiry/nbf/iat; metadata/challenges | Verified | SecuredGatewayIT rejects invalid/missing credentials on POST/GET/DELETE, zero REST calls; public metadata assertions |
| Explicit read/write permissions and private backend credentials | Verified | Allowed own read, denied registration/lease/payment writes, separate downstream token and log canaries |
| Tenant parent/child/region/owner boundaries | Verified | Foreign lookups, unapproved registration, mixed parents and forged owner pairs denied without REST calls |
| Session-owner and per-request identity | Verified | Foreign subject/tenant session rejection, expired token rejection, concurrent A/B and forged _meta tests |
| Fail-closed settings/policy and immutable startup snapshots | Verified | SecuredGatewayIT / SecurityPolicyTest; secured profile cannot disable authentication |
| Response loss, stable retry keys and uncertain outcomes | Verified | ResponseLossIT: 2 attempts/1 commit; new invocation/new key/second commit; deadline after commit gives unknown |
| Local and HTTP-disconnect cancellation after commit | Verified | CommittedCancellationTest + ResponseLossIT during wait/backoff; no further local attempts, no rollback |
| Migration, threat model and reproducible demonstration | Verified | ADR 0006, SECURITY_THREAT_MODEL.md, SECURED_MCP.md and executable independent fixture suite |

Tenant isolation is admission at this gateway, not tenant-aware backend storage.
JWT tests are resource-server evidence, not an interactive OAuth login/host flow.
Graceful SDK client closure did not cancel outstanding work in the experiment.
HAProxy failover, Inspector/host walkthrough and operations-assistant work remain
separate selected scopes. Both local planning documents remain excluded/unstaged.
