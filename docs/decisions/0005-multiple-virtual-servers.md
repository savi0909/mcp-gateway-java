# ADR 0005: Separate virtual MCP servers in one Boot process

**Status:** Proposed
**Date:** 2026-10-07
**Deciders:** Project owner

## Context

The user asked how to group tools by logical server, expose separate endpoints
and isolate catalogs while sharing one application process. The current combined
profile registers ten tools on one worker-coordinator SDK server. The pinned
starter's single-server auto-configuration aggregates global tool lists.

## Decision

Propose one SDK McpAsyncServer and one WebFlux Streamable transport provider per
logical server, managed together in one Boot process. Each has its own immutable
catalog, identity, routes and sessions. Compose SDK router functions on the shared
HTTP listener. Share the invoker, REST infrastructure and serialization code.
Use a version-4 catalog with server-scoped tool definitions; derive safe endpoint
paths from IDs. Keep backend/Origin/budget/resource policies private and scoped to
each server. Preserve existing profiles as a migration option.

The proposal does not authorize changing the running gateway, upgrading the SDK
or resuming paused tests. See [the detailed design](../MULTIPLE_VIRTUAL_SERVERS.md).

## Options considered

| Option | Complexity | Isolation | Operational effect |
| --- | --- | --- | --- |
| One SDK server with prefixes/filtering | Low initially | Shared discovery/session scope; filtering needs custom protocol behavior | Does not meet separate-server semantics |
| Separate SDK servers/providers in one JVM | Moderate | Separate catalogs and sessions; shared process resources | Meets requested process shape; recommended |
| Separate Boot processes | Moderate deployment overhead | Stronger process/resource isolation | Does not meet the requested single-process shape |

## Consequences

Explicit runtime lifecycle/routing replaces default single-server starter wiring
in multi-server mode. Worker tools remain at `/worker-coordinator/mcp`; payment
tools use `/payments/mcp`. Clients switching from the combined profile need two
connections. Tool names are unique within each server, allowing names to repeat
across servers. Session leakage and cross-server invocation require actual SDK
integration tests. Per-server limits reduce contention but do not isolate JVM
crashes, heap exhaustion or all CPU usage. Endpoint separation alone does not
authenticate or authorize callers.

## Action items

1. [ ] Implement immutable multi-server catalog/policy validation.
2. [ ] Extract the shared tool invoker and build managed server/provider pairs.
3. [ ] Compose routes and apply Origin checks to every registered endpoint.
4. [ ] Add endpoint-aware client commands and migration documentation.
5. [ ] When tests resume, prove discovery/tool/session/backend isolation through
       real SDK clients and independent local mocks; run full Wrapper verify.

## Evidence

Inspected resolved SDK 2.0.0 and Spring AI 2.0.1 common/WebFlux auto-configuration
sources plus the matching WebFlux transport source artifact. Confirmed instance
session storage, single-provider injection, router exposure and explicit async
server construction APIs. Consulted official Spring AI documentation and the
2025-11-25 negotiated-protocol transport rules. Architecture only: no production
source changes, build or test execution and no running-process changes.
