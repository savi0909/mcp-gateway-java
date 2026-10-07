# Milestone 1 implementation plan

The baseline is `FUNCTIONAL_SPEC.md` and `DESIGN.md` section 10. This file tracks work; it does not replace requirements. Status is `pending`, `in progress`, or `complete`, and completion requires the stated evidence.

User-directed 2026-10-07 exception: pause automated tests, allow automatic allocation retries, and use the existing separate payment sample for the local MCP demo. See ADRs 0002/0003. Default MCP stays disabled; the opt-in payments profile is operational. The original milestone is not fully verified.

Subsequent requested scope: add worker APIs as tools and definitions. ADR 0004
adds version-3 catalogs for nine coordinator APIs, or ten tools including payment,
with explicit body/path mappings and separate backend settings. Implementation
and test compilation are complete; actual combined-profile SDK discovery passed.
Worker invocation evidence and full automated verification remain pending under
the continuing test pause. Original milestone stage statuses below stay partial.

| Stage | State | Required result | Completion evidence |
| --- | --- | --- | --- |
| Development setup | Complete | Shared AI instructions, workflow, build, Wrapper, restored catalogs and planning files | Wrapper verification, prerequisite/document checks and recorded worklog evidence |
| 1A - Stack and Boot skeleton | Complete | Pinned compatible build; executable local Boot; resolved SDK/API/property inventory | AC-01 and `docs/DEPENDENCY_BASELINE.md`; no model credentials |
| 1B - REST adapter | In progress | Typed validated runtime settings; bounded WebClient execution; independent controllable HTTP mock; precise ID mapping | Implemented and packaged; final revised retry tests and Wrapper `verify` deferred |
| 1C - Catalog and registration | In progress | Strict immutable startup catalog; programmatic ASYNC specs; public metadata only; MCP enabled with valid registry | Payments-profile SDK discovery passed; automated invalid-catalog/lifecycle evidence pending |
| 1D - MCP-to-REST behavior | In progress | Dispatcher, SDK success/errors, Origin enforcement, cancellation and terminal logging | Two live local payment SDK calls passed; error/Origin/cancellation integration tests pending |
| 1E - Reproducible local demo | In progress | Standalone REST mock, discovery-default SDK smoke client with explicit `--allocate`, external config and final README | Real local Docker payment demo and SDK client exist; standalone mock and final `verify` pending |

## Scaffold boundary

The Boot entry point, build, Failsafe wiring, typed deployment properties and matching original catalog files exist. Default MCP is disabled deliberately. The opt-in `payments` profile enables validated single-tool registration and actual MCP-to-payment execution. `ScaffoldStartupTest` still guards the default disabled endpoint. See the worklog for the successful live SDK smoke and deferred automated verification.

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
- Prove one upstream attempt, exact ID preservation, bounded failures, no canary leaks and one safe terminal log per invocation.
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
- Evidence: Wrapper `verify -DskipTests` compiles source and tests and packages;
  actual running Boot/SDK discovery lists all ten tools using protocol `2025-11-25`.
- Pending execution: `FileCatalogLoaderTest`, `CoordinatorToolsIT` and full Wrapper
  `verify` when the user resumes tests. No live worker mutations were performed.

### Remaining original inputs

| Item | Current handling |
| --- | --- |
| Real coordinator method/path/auth/body/response | Inspected local repository: leases rather than UUID generation. User selected the separate payment API; see ADR 0003 and `REAL_COORDINATOR_CONTRACT.md` |
| Group/package | Scaffold default `dev.mcp.gateway`, artifact `mcp-gateway-server`; no organization-specific requirement supplied |
| Live integration authorization | User explicitly authorized local Docker coordinator/payment demo; two live SDK-created sample payments succeeded. No production allocation authorization |
| Remote Git host/CI | Not supplied; local Git and portable verification workflow only |

Do not add custom query/header/business-argument mappings to guess a missing real contract. Ask when that contract becomes necessary. Deferred milestone items remain deferred.
