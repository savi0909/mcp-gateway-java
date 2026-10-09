# UC-01 implementation handover

Prepared 2026-10-09. Implementation baseline: `b25a205` (`Implement local UC-01
identity and admission boundaries`), pushed to `origin/main`. The working tree
was clean and local/remote commits matched before this documentation handover.
This handover does not start another implementation increment.

## Read first

1. [Repository instructions](../AGENTS.md), [current state and latest worklog entry](../WORKLOG.md),
   and [implementation plan](IMPLEMENTATION_PLAN.md).
2. [Identity/authorization v1.4](requirements/Enterprise_MCP_Identity_Authorization_Requirements_v1.4.md)
   and [discovery/catalog v1.3](requirements/Enterprise_MCP_Discovery_Catalog_Requirements_v1.3.md).
   Catalog v1.3 was supplied, reviewed and transcribed; it is no longer a missing dependency.
3. [UC-01 implementation and configuration guide](UC01_ADMISSION.md),
   [ADR 0009](decisions/0009-uc01-local-admission-design.md), and [slice plan](UC01_ADMISSION_PLAN.md).
4. [Identity acceptance](IDENTITY_ACCEPTANCE.md), [catalog acceptance](CATALOG_ACCEPTANCE.md),
   and [foundation acceptance](ACCEPTANCE.md). Keep their identifiers and evidence separate.
5. Before Java changes, also read [implementation prompt](IMPLEMENTATION_PROMPT.md),
   [functional specification](FUNCTIONAL_SPEC.md), [design](DESIGN.md), and
   [original catalog](../examples/worker-catalog.json). Preserve their foundation behavior.

## Implemented checklist

The opt-in `workers,secured,uc01` profiles support internal human-delegated
ordinary reads. Default MCP remains disabled; existing demo profiles are separate.

1. Separate approved application and authority-scoped human identities, active
   organization/tenant membership, scoped administration, pending enrollment,
   lifecycle enforcement and prevention of self-activation/self-grants.
2. Explicit application catalog membership, current publication and approved
   server origin, pinned contracts/fingerprints, per-user discovery and independent
   authorization of manually constructed calls. Publication grants no membership.
3. Intersect current application, human and delegation permissions with independent
   resource ownership. Select an exact context credential and force admission audit
   before one bounded GET attempt. Return only approved output fields.
4. Fresh authorization at subscription, effective local revocation, bounded verified
   policy fallback, durable outcome evidence and restart recovery without replay.

Administration tokens use a separate `ADMINISTRATION` mode and cannot invoke
business tools. Sensitive reads, mutations, autonomous workloads and third-party
execution remain disabled in UC-01. This is neither a complete IAM system nor a
complete enterprise gateway release.

## Code entry points

| Responsibility | Entry point |
| --- | --- |
| Policy records, identities and lifecycle | [AdmissionPolicy](../src/main/java/dev/mcp/gateway/admission/AdmissionPolicy.java), [AdmissionIdentity](../src/main/java/dev/mcp/gateway/admission/AdmissionIdentity.java) |
| Authorization, administration, audit and execution ordering | [AdmissionService](../src/main/java/dev/mcp/gateway/admission/AdmissionService.java) |
| Forced local journals and recovery | [AdmissionJournal](../src/main/java/dev/mcp/gateway/admission/AdmissionJournal.java) |
| Verification age, revision integrity and outage behavior | [VerifiedPolicyCache](../src/main/java/dev/mcp/gateway/admission/VerifiedPolicyCache.java) |
| Contract semantics and private binding pinning | [ContractFingerprint](../src/main/java/dev/mcp/gateway/admission/ContractFingerprint.java) |
| Caller-filtered discovery through public SDK SPI | [FilteredMcpTransport](../src/main/java/dev/mcp/gateway/admission/FilteredMcpTransport.java) |
| Session identity isolation | [AdmissionSessionFilter](../src/main/java/dev/mcp/gateway/admission/AdmissionSessionFilter.java) |
| Opt-in beans, control routes and private settings | [AdmissionConfiguration](../src/main/java/dev/mcp/gateway/admission/AdmissionConfiguration.java), [AdmissionSettings](../src/main/java/dev/mcp/gateway/admission/AdmissionSettings.java), [profile](../src/main/resources/application-uc01.yml) |
| One-attempt authorized reads and legacy execution | [RestBindingExecutor](../src/main/java/dev/mcp/gateway/rest/RestBindingExecutor.java) |
| Real Boot/SDK evidence | [Uc01AdmissionIT](../src/test/java/dev/mcp/gateway/admission/Uc01AdmissionIT.java) |
| Deterministic expiry, uncertainty and policy-cache evidence | [AdmissionBoundaryTest](../src/test/java/dev/mcp/gateway/admission/AdmissionBoundaryTest.java), [VerifiedPolicyCacheTest](../src/test/java/dev/mcp/gateway/admission/VerifiedPolicyCacheTest.java) |

The SDK owns negotiation, sessions, envelopes and unknown tools. Discovery uses
typed SDK handlers; do not replace it with handcrafted JSON-RPC or shared per-user
tool-list mutation. Backend REST services remain unchanged.

## Verification baseline

The final implementation verification completed on **2026-10-08 at
18:14:18 +05:30**:

```powershell
.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' verify
```

Result: **128 Surefire unit/startup tests and 34 Failsafe integration tests;
zero failures, errors or skips; BUILD SUCCESS**. The new slice contributes
7 unit tests and 14 real Boot/SDK integration cases. The original 121/20 foundation
tests remain included.

The retained local log is `target/uc01-verify.log`; the packaged executable is
`target/foundation/mcp-gateway-server-0.1.0.jar`. Both are ignored build artifacts,
not source handover dependencies. Old focused Surefire IT reports remain, so use
the final Maven summaries rather than summing every retained report.

Evidence includes per-user discovery/direct-call denial, exact credential use,
contract pinning, independent catalog removal, session and revocation checks,
audit failure before dispatch, uncertain outcome recovery, exact expiry boundaries,
and 100 interleaved reads across 50 users, two applications and two tenants.
Tests use independent local issuer/REST mocks and random ports. These are
functional isolation checks, not throughput benchmarks or live provider evidence.

For this docs-only handover, the existing log and artifact were inspected;
Maven was not rerun. No demo was restarted, no live allocation was made, and no
deployment occurred. Current running-service health was not checked. An existing
process does not automatically acquire the new JAR.

## Boundaries to preserve

| Boundary | Current behavior |
| --- | --- |
| Last verified policy | At most 30 seconds using monotonic time; cached reads and failed refreshes cannot extend it. Invalid/rolled-back/conflicting policy fails closed. |
| Administrative changes | Require authoritative policy reads; no writes using fallback. Accepted/effective local completion is synchronous. |
| Revocation | v1.4 requires completion within 60 seconds. Local committed changes invalidate permission immediately; existing sessions and not-yet-admitted work are rechecked. Already admitted work may finish. |
| Delegation | Explicit human/application/tenant scope; maximum 15 minutes; expiry and current status checked per invocation. |
| Downstream credential | Separate exact-context reference, approved backend audience and expiry; no inbound token passthrough or shared-account fallback. |
| Execution | Ordinary internal GET only; one application attempt with bounded deadlines/response size. Missing outcome evidence remains unknown. |
| Persistence | Single process, exclusive writer locks, append/force writes; each journal limited to 16 MiB. Capacity or audit failure prevents new dispatch. |
| Audit retention | 90-day visibility/retention metadata; physical expiry, archival and compaction are not implemented. |
| Catalog/environment | One catalog per application identity; explicit membership and approved version, no silent substitution. |

Do not inherit legacy payment retries for future approved mutations. UC-02/AC-27
require separate handling of uncertain mutations; rechecking authorization does
not make reuse of an approval safe. Preserve gateway audience validation and
separate downstream credentials in every later execution mode.

## Remaining work and release status

U1/U3/U4 are complete for the documented local ordinary-read scope; U2 is
implemented within its administrative limits; **U5 remains Partial**. All
**32 identity baseline criteria** and applicable MUST behavior remain required
before declaring the full first release complete. Catalog-domain completion also
requires its separate acceptance evidence.

Remaining gaps include:

- Positive workload reads, sensitive-action approvals and approved third-party
  execution, with revocation/evidence for each newly supported context.
- Durable audit coverage for authentication failures before enterprise identity
  extraction, controlled physical expiry/archival, and full outcome/retention evidence.
- Live downstream issuer/scope verification, credential rotation and token exchange.
  Current opaque credentials rely on trusted provisioning; known-secret echo checks
  do not provide general response-content policy or data-loss prevention.
- Multi-tenant principal administration and organization/tenant topology operations.
- Distributed configuration/revocation, HA, capacity benchmarks, backup rollback
  recovery and power-loss durability proof. Local journal guarantees do not prove these.
- Native MCP federation, catalog-change notifications and newer protocol/Tasks
  support. The pinned SDK retains its 2025-11-25 session model; no transparent
  recovery of a broken filtered response stream is supplied.

Gateway admission does not make backend storage tenant-aware. Keep Java 21,
Boot 4.0.8, Spring AI BOM 2.0.1, BOM-managed SDK 2.0.0 and Wrapper Maven 3.9.11.
Inspect matching dependency sources before API changes. Do not add vendors or
upgrade dependencies merely because they appear in the future enterprise vision.

## Recommended next implementation

Review the remaining UC-01/U5 gaps first, then design **UC-03 ordinary workload
reads and mixed-context isolation** as the next feature increment. An autonomous
workload needs its own approved identity, owner, organization, tenant scope,
associated application and lifecycle. Intersect application/workload grants with
catalog eligibility, resource ownership and exact downstream credentials.
Do not fabricate a represented human or fall back between execution modes.

Reuse durable admission ordering, current verification, bounded outage behavior,
independent ownership and local revocation. Extend real Boot/SDK fixtures to mix
delegated and workload contexts and prove denials execute zero business calls
and return zero protected output. Keep sensitive/autonomous mutations and
unsupported third-party execution disabled. Record unresolved U5 gaps explicitly.

Copyable prompt for a future, explicitly requested implementation session:

```text
Read AGENTS.md, WORKLOG.md, docs/IMPLEMENTATION_PLAN.md and
docs/HANDOVER_UC01.md. Review identity v1.4, catalog v1.3, the UC-01 guide,
ADR 0009, and both enterprise acceptance trackers; inspect current Git state.

Review remaining UC-01/U5 gaps, then design and implement the UC-03 ordinary
internal workload-read slice and mixed delegated/workload isolation. Update the
relevant design, plan and acceptance mapping before coding. Use separate approved
application/workload identities with explicit execution mode and no represented
human or mode fallback. Preserve catalog pinning, resource ownership, exact-context
credentials, durable admission audit, fresh checks, revocation and bounded fallback.
Keep sensitive reads, mutations and unsupported third-party execution disabled.

Preserve existing foundation/demo behavior and the pinned dependency baseline.
Inspect resolved sources for SDK APIs. Use independent random-port mocks and real
Boot/SDK clients; verify negative request counts, context isolation and expiry
boundaries. Run the full Wrapper verify with gateway.build-directory=target/foundation.
Update trackers only where evidence exists, append exact results and limits to
WORKLOG.md, then commit and push under the standing repository instruction.

Do not delete files, run clean, restart demos, allocate live IDs, deploy or spawn
agents. Do not claim full-release completion while baseline criteria remain unmet.
```
