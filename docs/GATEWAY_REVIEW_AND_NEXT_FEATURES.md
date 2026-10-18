# Gateway implementation review and proposed next features

Date: 2026-10-18. Reviewed product revision: `0278f74`.
Scope: focused source, requirements and evidence review; proposed product roadmap.
This is not an exhaustive security audit or authorization to implement every phase.
Development harness work is outside this repository (ADR 0011).

## Assessment

The project is a working local MCP-to-REST gateway with a meaningful delegated-read
admission layer. It is not yet a federated, highly available enterprise platform.
Preserve the existing foundation rather than rebuild it. The immediate priority
is to finish the supported admission boundary, then add workload reads and the
registry/virtual-server architecture needed for broader federation.

| Area | Implemented and evidenced | Remaining boundary |
| --- | --- | --- |
| Protocol and adapters | SDK-owned MCP, ASYNC handlers, immutable validated startup catalogs, explicit REST mappings; one/nine/ten tools in opt-in profiles | One virtual endpoint; no native MCP upstream adapter or dynamic server registry |
| Execution | Deadlines, response bounds, no redirects/connector retries, exact integer/string IDs, sanitized errors; UC-01 reads use one attempt | Tenant fairness, admission queue budgets, circuit breaking and cluster limits are not established |
| Foundation security | Origin, JWT issuer/audience/type/expiry, scopes, tenant/resource policy and session ownership | Optional demo profiles remain available; backend storage isolation is a separate obligation |
| UC-01 | Application/human separation, grant intersection, resource ownership, catalog membership, pinned contract/origin, isolated credentials, forced admission audit, current local revocation | Only delegated ordinary internal reads; full first-release identity acceptance remains incomplete |
| Governance | Bounded administration, revision checks, ordinary membership and contract pinning | Approval workflows, compatibility classification, retirement notices and complete catalog lifecycle evidence |
| Operations | Independent mocks, real Boot/SDK integration, restart/unknown-outcome evidence | Distributed state/revocation, recovery from backup, production durability, capacity and availability evidence |

Primary code reviewed: `AdmissionService`, `AdmissionJournal`,
`VerifiedPolicyCache`, `AdmissionConfiguration`, `AdmissionSessionFilter`,
`FilteredMcpTransport`, `SecurityConfiguration`, `RestBindingExecutor` and
`McpToolRegistrationConfiguration`; relevant UC-01 integration test coverage.
See [identity acceptance](IDENTITY_ACCEPTANCE.md),
[catalog acceptance](CATALOG_ACCEPTANCE.md) and [current handover](HANDOVER_GATEWAY.md).

## Findings that shape the next increment

1. **P1 — Authentication/session rejections lack durable admission audit.**
   `SecurityConfiguration.java:66–75` returns 401 directly;
   `AdmissionSessionFilter.java:66–68` returns 403 directly. These paths never
   reach `AdmissionService.admit`, where invocation decisions are persisted.
   Invalid-token and session-owner rejection attempts therefore lack this durable
   decision evidence. Add bounded, sanitized rejection auditing with unverified
   identity clearly distinguished; never log bearer tokens or trust decoded claims.
   Prove denial and audit behavior through HTTP/SDK fixtures, including audit failure.
   This is an acknowledged OP-04/IA-AC-24 coverage gap, not a newly demonstrated bypass.
2. **P1 — Journal capacity is a predictable service stop without a lifecycle path.**
   `AdmissionJournal.java:22,93` caps each append-only journal at 16 MiB.
   Audit capacity exhaustion prevents new dispatch; policy capacity exhaustion
   prevents administration commits. Retention timestamps do not reclaim storage.
   Add controlled rotation/archive/checkpoint recovery with capacity visibility,
   preserve the 90-day retention requirement, and audit lifecycle operations.
   Demonstrate full-disk/capacity, interrupted rotation, restart and restore behavior.
   Do not solve this by deleting active evidence or merely increasing the bound.
3. **P2 — Admission cost grows with policy history and serializes callers.**
   `VerifiedPolicyCache.current()` attempts a fresh read each time;
   `AdmissionJournal.readPolicy()` reads and deserializes every historical snapshot.
   Discovery and admission call it while holding the shared journal monitor
   (`AdmissionService.java:64–68,100–111`). This is a source-supported scaling concern,
   not a measured latency regression. Use validated current snapshots/checkpoints
   and bounded admission scheduling while preserving authoritative writes,
   freshness, audit-before-dispatch and the revocation ordering contract.
4. **Architecture boundary — local state cannot simply become two replicas.**
   Exclusive journal locks intentionally enforce a single writer; SDK sessions are
   local. Sharing the directory cannot provide HA, and separate uncoordinated
   directories cannot provide common policy/revocation. Specify durable state,
   publication, session routing and partition behavior before claiming E1 HA.

The existing 100-call/50-user isolation test is valuable functional evidence. It
does not measure sustained throughput, noisy-neighbor fairness or journal growth.
Known-secret echo checks and fixed response projection are also not general PII
redaction or a data-loss prevention system.

## Uber comparison

Primary reference, accessed 2026-10-18:
[Uber Engineering — Designing MCP Gateway](https://www.uber.com/pr/en/blog/designing-mcp-gateway/),
published 2026-10-01.

Uber separates registry/authoring from runtime execution. Its article describes
API-schema crawling and native MCP discovery, disabled initial registrations,
owner-reviewed changes, virtual-server endpoints, configuration refresh,
protocol adapters, tool authorization and response redaction. It also describes
incremental discovery through Omni MCP, response projection and a CLI for coding
agents. It reports over 800 servers and 5,000 tools; those figures are Uber's
deployment claims, not this project's capacity targets or evidence.

Replicate those architectural capabilities through independently designed
contracts. Do not copy Uber-specific service-mesh, IDL infrastructure or token
forwarding assumptions. Our baseline explicitly requires separate downstream
credentials. Treat the article as inspiration, not a complete implementation or
security specification.

## Custom requirements to preserve

The supplied identity v1.4 and catalog v1.3 already define substantial custom
behavior: explicit application membership; per-human/workload filtering; pinned
contracts; independent application and human/workload authority; separate sensitive
membership and action approvals; resource ownership; scoped credentials; durable
pre-execution evidence; 60-second revocation; 30-second maximum reusable decision
age; and no replay of uncertain approved mutations.

Keep registration, publication, entitlement, discovery and invocation as distinct
states. An imported schema or generated description grants no authority. Neither
search nor a generic invocation facade may bypass these rules. No additional
custom enhancements are assumed beyond the supplied requirements.

## Proposed delivery order

These packages refine the existing U/E roadmap; they do not mark any pending
criterion complete. Each needs a bounded implementation contract and relevant
acceptance mapping before coding. Preserve the pinned Java/Boot/AI/SDK baseline
unless a separate compatibility decision proves an upgrade necessary.

| Order | Deliverable | Exit evidence |
| --- | --- | --- |
| A — Finish supported UC-01/U5 | Rejection audit; journal lifecycle/recovery; bounded admission scheduling; full supported revocation matrix; ordinary catalog activation/owner-denial evidence | Zero backend executions/protected output on denial; durable decisions; controlled retention; restart/capacity tests; revocation within 60 seconds including queued calls; no stale decision reuse beyond 30 seconds |
| B — UC-03 workload reads | Distinct workload identity/owner/application/tenant lifecycle; workload grant intersection; exact workload credentials; mixed human/workload discovery and execution | Authorized ordinary read with no fabricated human; mode-fallback/impersonation denials; workload revocation/expiry; mixed-context isolation; IA-AC-10 and CAT-E2E-11 plus applicable shared criteria |
| C — Registry and virtual servers | Control-plane domain/API, owner-reviewed publication, immutable contract versions, explicit app catalogs, validated atomic snapshots, multiple isolated virtual endpoints | Three synthetic service namespaces; duplicate local names remain isolated; consistent catalog generation; cross-server session/credential denial; invalid update rejected; retirement never substitutes a version |
| D — Native federation and HA | Native MCP adapter alongside REST; approved upstream discovery; destination controls; durable publication/audit state; two runtime replicas with explicit session strategy | Three independent mock MCP upstreams; discovery causes zero business calls; scoped upstream credentials; bounded upstream failure; one replica dies and surviving replicas serve new calls; partition/cold-start/stale-state/revocation tests |
| E — Baseline approvals and providers | UC-02 independent human approval bound to exact action, single-use admission and uncertainty reconciliation; approved third-party ordinary reads with constrained disclosure | Concurrent approval consumption admits at most once; self-approval/changed action/expiry denied; uncertain mutation never auto-replays; provider withdrawal and credential isolation; complete remaining baseline identity/catalog criteria |
| F — Enterprise operations and discovery UX | Tenant quotas/concurrency/queue limits, tool circuit breaking, traces/metrics, controlled rollout/rollback, registry UI, incremental search/schema retrieval and optional CLI | Tenant saturation isolation, bounded telemetry labels, audit reconstruction, rollback cannot restore revoked permissions, reproducible load/failure report; every search/schema/invoke path enforces the same policy |

Observability and resource bounds needed for safety are implemented in each
package; F expands their operational coverage, not their first appearance.
The ordering of E after limited read-only federation does not authorize sensitive
or third-party execution earlier, or relax the full first-release gate.

For C, first evolve the existing [multiple virtual servers proposal](MULTIPLE_VIRTUAL_SERVERS.md)
to include UC-01/UC-03 identity and catalog semantics. Its older single-foundation
assumptions are not sufficient alone. Choose persistence/publication and deployment
boundaries in an ADR; a separate control plane does not automatically require
splitting every package into a service. PostgreSQL, an OIDC provider and a policy
engine remain candidates, not silently selected dependencies.

API import should follow validated registry publication: start with a restricted
OpenAPI/HTTP subset, then add Protobuf/gRPC only for a concrete backend need.
Imports create disabled drafts; unsupported schemas fail visibly. TChannel/Thrift,
LLM-generated descriptions, arbitrary code execution and protocol Tasks are later
optional extensions. The proposed CLI invokes gateway capabilities and is not a
development-automation harness.

## Immediate implementation scope

Recommend **package A first**, using separate reviewable changes for rejection
audit, journal lifecycle/recovery, and revocation/catalog evidence. Then B provides
the next new caller capability, and C provides the next major platform capability.
Do not restart existing demos, change the coordinator, enable sensitive operations
or deploy infrastructure as part of this review.

Each change should retain independent random-port fixtures and real Boot/SDK tests,
run Maven Wrapper verification, and update only acceptance rows fully supported
by executed evidence. Package A closes the supported local scope; it cannot by
itself complete all 32 identity criteria or the full enterprise release.

## Verification

Fresh command: `.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/gateway-review-20261018' verify`.
Result: BUILD SUCCESS; 128 unit/startup and 34 integration tests, zero failures,
errors or skips. Log: `target/gateway-review-20261018.log`; packaged JAR and reports
are under `target/gateway-review-20261018`. These are independent local mock and
Boot/SDK results. No hosted CI, live provider, production scale or deployment
verification is claimed.
