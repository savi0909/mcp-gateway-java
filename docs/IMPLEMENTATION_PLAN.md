# Milestone 1 implementation plan

The baseline is `docs/FUNCTIONAL_SPEC.md` and `docs/DESIGN.md` section 10. This file tracks work; it does not replace requirements. Status is `pending`, `in progress`, or `complete`, and completion requires the stated evidence.

The owner-selected [enterprise vision](ENTERPRISE_MCP_VISION.md) and
[ADR 0007](decisions/0007-enterprise-platform-direction.md) guide future planning.
Enterprise E1-E4 (federation, security/policies, transport/resilience and production
control plane) are pending and separate from this completed foundation milestone.
Recording that direction does not begin implementation or change acceptance rows.

User-directed exceptions allow bounded application retries and the separate
payment sample (ADRs 0002/0003). On 2026-10-07 the owner explicitly resumed
independent local tests and selected the secured foundation handover. Full Wrapper
verification now passes: 121 unit/startup tests and 20 integration tests, zero
failures/errors/skips. Default MCP stays disabled; existing running demos were
preserved. See ADR 0006 for the separately selected security extension.

Subsequent requested scope: add worker APIs as tools and definitions. ADR 0004
adds version-3 catalogs for nine coordinator APIs, or ten tools including payment,
with explicit body/path mappings and separate backend settings. Implementation
and test compilation are complete; actual combined-profile SDK discovery passed.
All worker mappings now have running Boot/SDK mock invocation evidence. Full
automated verification and the standalone independent process demo passed.

| Stage | State | Required result | Completion evidence |
| --- | --- | --- | --- |
| Development setup | Complete | Shared AI instructions, workflow, build, Wrapper, restored catalogs and planning files | Wrapper verification, prerequisite/document checks and recorded worklog evidence |
| 1A - Stack and Boot skeleton | Complete | Pinned compatible build; executable local Boot; resolved SDK/API/property inventory | AC-01 and `docs/DEPENDENCY_BASELINE.md`; no model credentials |
| 1B - REST adapter | Complete | Typed validated settings, bounded WebClient, independent mock and precise mapping | Final executor/configuration tests pass; retries/deadlines/local cancellation evidenced |
| 1C - Catalog and registration | Complete | Strict immutable startup catalog, ASYNC specs and public metadata only | FileCatalogLoaderTest/CatalogProtocolIT/CoordinatorToolsIT; invalid Boot startup, fresh-context changes and outage-independent discovery |
| 1D - MCP-to-REST behavior | Complete | SDK success/errors, Origin, cancellation and safe terminal logging | All mapping/Origin/error/response-loss SDK integration checks pass; physical HTTP reset cancels local work; graceful SDK closure limitation recorded |
| 1E - Reproducible local demo | Complete | Standalone mock, discovery-default SDK client, external config and README | Separate random-port processes: discovery 0 allocations, 2 explicit calls 2 allocations; full Wrapper verification/package passes |

## Scaffold boundary

Default MCP stays disabled deliberately and ScaffoldStartupTest guards that
boundary. Opt-in catalog profiles have actual protocol evidence. Tests were
resumed, CI executes them, and separate `target/foundation` packaging avoids
the existing demo JAR. The local/live historical smoke and independent mock
foundation evidence are distinguished in the worklog.

The 1A source/metadata inventory is recorded in `docs/DEPENDENCY_BASELINE.md`. The resolved SDK is 2.0.0. Registration/client signatures and starter property names were inspected; their actual use still requires compilation and protocol tests in 1C/1D. Stage 1B must inspect and verify the HTTP connector's retry, redirect, timeout and response-limit behavior.

## Stage tasks

### 1B

- Implement immutable deployment properties and validate backend origin/budgets/body bounds.
- Execute only the static catalog-supported GET/POST request using WebClient, with optional private bearer token.
- Explicitly disable connector retries and redirect following; no allocation cache. User-authorized application retries now share the total upstream deadline (ADR 0002).
- Extract string/integral IDs by validated JSON Pointer without floating-point conversion; return categorized sanitized failures.
- Use a separate local HTTP fixture with controllable errors/delays, request capture and counters. Never contact the real coordinator in tests.

### 1C

- Read a configurable classpath/file resource once; reject duplicate JSON keys and unsupported/unknown fields, schemas, bindings and cardinalities.
- Validate catalog ID, configured logical server and fixed endpoint alignment.
- Register file-derived metadata and ASYNC handlers through actual resolved SDK/starter APIs; keep other discovery/registration mechanisms disabled.
- Enable MCP together with validated registration. Replace the scaffold-only disabled-endpoint assertion with protocol discovery/startup tests.
- Test fresh contexts for catalog changes, invalid configurations and backend-independent discovery.

### 1D

- Validate empty arguments before any REST call; leave protocol/lifecycle/unknown tools to the SDK.
- Return matching text/structured success and sanitized error text without error structured content.
- Enforce allowed Origin on the MCP endpoint; permit clients without Origin.
- Prove exact attempts under ADR 0002's bounded retry policy, exact ID preservation, bounded failures, no canary leaks and one safe terminal log per invocation.
- Add real protocol integration tests named `*IT` using random server ports and bounded SDK-client calls.

### 1E

- Supply a separate REST demo process; only that fixture generates demo IDs.
- Supply a compatible SDK smoke client that discovers by default and allocates only with explicit `--allocate`.
- Verify the mock flow. Document uncertain allocation outcomes and the difference between mock-tested and live-tested contracts.
- Update README with process commands, external catalog/env settings, component walkthrough, negotiated protocol/dependencies and actual test results.

## Open inputs and assumptions

### User-directed worker tool extension

- Completed implementation: inspected DTOs/controllers, nine public tool schemas,
  immutable multi-tool startup catalog, input validation, explicit safe mappings,
  response projection and `workers`/`worker-payments` profiles.
- Evidence: full Wrapper verification/package and FileCatalogLoaderTest /
  CoordinatorToolsIT pass; real SDK clients verify all ten tools against independent
  random-port mocks using protocol `2025-11-25`. No live worker mutations occurred.

### Selected security/failure foundation (complete within its boundary)

- ADR 0006, threat model, opt-in secured profile and immutable tenant admission.
- SecuredGatewayIT / SecurityPolicyTest prove authentication, scopes, full namespace
  and owner/session denial, concurrent caller context and fail-closed settings.
- ResponseLossIT / CommittedCancellationTest distinguish HTTP attempts from logical
  commits, stable retry keys, new invocation keys, unknown outcomes and cancellation.
- Secured guide provides migration, focused demonstration commands and explicit
  limitations. Enterprise IAM, backend storage tenancy, interactive host/OAuth
  walkthrough and controlled HAProxy failover remain separate scopes.

### Remaining original inputs

| Item | Current handling |
| --- | --- |
| Real coordinator method/path/auth/body/response | Inspected local repository: leases rather than UUID generation. User selected the separate payment API; see ADR 0003 and `REAL_COORDINATOR_CONTRACT.md` |
| Group/package | Scaffold default `dev.mcp.gateway`, artifact `mcp-gateway-server`; no organization-specific requirement supplied |
| Live integration authorization | User explicitly authorized local Docker coordinator/payment demo; two live SDK-created sample payments succeeded. No production allocation authorization |
| Remote Git host/CI | Published origin/main exists; CI executes full checks again, but no hosted CI result is claimed |

Do not add custom query/header/business-argument mappings to guess a missing real contract. Ask when that contract becomes necessary. Deferred milestone items remain deferred.
