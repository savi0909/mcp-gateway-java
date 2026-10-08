# UC-01 admission slice: selected next implementation

Date: 2026-10-08. Status: opt-in local implementation with executed mock evidence.
[The implementation guide](UC01_ADMISSION.md) documents supported behavior and
limits. Full identity/catalog domain completion remains separate.

## Baseline and scope

The authoritative enterprise identity behavior is the preserved
[requirements v1.4](requirements/Enterprise_MCP_Identity_Authorization_Requirements_v1.4.md).
[ADR 0008](decisions/0008-uc01-admission-first.md) records sequencing. The
[enterprise vision](ENTERPRISE_MCP_VISION.md) remains the long-term direction;
this slice precedes broad federation. The original functional specification and
its completed foundation evidence remain unchanged.

Design and implement **UC-01, human-delegated ordinary read**, with identity and
tenant lifecycle, minimal catalog eligibility, isolated downstream credentials,
durable admission audit and revocation. These are one end-to-end increment.
Registration or catalog membership alone cannot grant execution authority.

The first supported execution mode is an approved internal application's
human-delegated, non-sensitive read against an approved internal server. Reject
unsupported execution modes, unclassified tools, sensitive reads, mutations and
third-party execution in this slice. Existing legacy demos remain separate;
their permissions/retry behavior are not the enterprise admission contract.

All v1.4 MUST requirements and **all 32 baseline acceptance criteria** remain
required for the full first release. A successful UC-01 slice is not full-release
completion or permission to expose unsupported behavior. The separate
[identity acceptance tracker](IDENTITY_ACCEPTANCE.md) uses IA-AC-01 through
IA-AC-32 to avoid collisions with foundation AC-01 through AC-13.

## Reviewed catalog dependency

The owner supplied [catalog requirements v1.3](requirements/Enterprise_MCP_Discovery_Catalog_Requirements_v1.3.md)
in conversation; reviewed and transcribed on 2026-10-08. Explicit membership,
separate sensitive approval, per-user filtering and pinned contracts govern this
increment. [Catalog evidence](CATALOG_ACCEPTANCE.md) keeps its identifiers separate
from IA-AC and foundation AC rows. The foundation's static REST catalog is an
execution adapter, not a substitute for these enterprise requirements.

## Ordered work

| Stage | State | Scope and required result |
| --- | --- | --- |
| U1 - Contract and design | Complete for local scope | Reviewed v1.3; ADR 0009 selects verified JWT identity, single-writer journals, forced admission audit, exact scoped credentials, 30-second fallback and SDK SPI filtering. Inspected pinned dependency sources; no version changes. |
| U2 - Identity and administration | Implemented; bounded scope | Separate approved apps/humans, organization/tenant checks, pending enrollment, lifecycle, roles, no self-activation/grants, immutable retired identities, audited administration. Multi-tenant topology administration and workload lifecycle remain later. |
| U3 - Minimal catalog eligibility | Complete for ordinary internal reads | Explicit owner request/removal, separate publication, classified immutable contracts, exact approved origin, fingerprint/schema pinning, filtered SDK discovery and direct-call enforcement. Sensitive approval stays pending/disabled. |
| U4 - Delegated read admission | Complete for ordinary internal reads | Intersected exact app/human/delegation/resource grants, expiry, current checks, scoped credentials, one GET attempt, forced admission evidence, protected output projection and uncertain-outcome recovery. |
| U5 - Revocation and evidence | Partial | Real Boot/SDK fixtures prove local revocation, deferred-subscription checks, audit outage, expiry boundaries, session isolation and 100 calls/50 users. Wrapper verification passes. Physical audit expiry/archival, all revocation types and distributed guarantees remain unimplemented/unproven. |

The stages describe dependency order, not independent deployable products.
Durable audit belongs in administration and read admission from their first
implementation; revocation semantics belong in U1 and are exercised throughout.
Do not add audit or revoke checks only after tool execution has been enabled.

## Implemented admission contract

```text
SDK request with verified application + represented human
  -> active organization, tenant and principal membership
  -> approved server, ordinary-read classification, publication and contract
  -> application catalog membership and discover/invoke eligibility
  -> current application permission AND human permission
  -> current delegation scope AND tenant/resource/data policy
  -> approved destination and context-scoped downstream credential
  -> fresh execution-admission checks and durable admission audit
  -> one requested read execution
  -> validated allowed result and durable outcome evidence
```

ADR 0009 defines local concurrency semantics: fresh evaluation and forced audit
serialize with administration, and successful admission audit defines the
admission point. External execution is a separate commit. Preserve admitted work
and unresolved outcomes across failures; a missing response is not proof of
non-execution. Control-plane administration has separate mode/roles and does not
depend on a business delegation.

Trusted context must distinguish organization, tenant, approved application,
represented human, delegation reference, execution mode and attribution-only
agent/correlation identifiers. Prompts, tool arguments, arbitrary headers and
session identifiers must not supply authority. Default to one tenant per
organization while modeling both identifiers and testing authorized multi-tenant
membership. Do not hardcode sample users, tool names or resources as policy.

Validate access evidence for the intended recipient and approved authority.
Application approval, sign-in, publication, a previous call and a catalog response
each remain insufficient independently. Unknown required attributes/conditions
produce verification-unavailable or denial as appropriate, not permissive fallback.
Return distinct sanitized authentication, denial and verification outcomes without
protected output; do not prescribe HTTP/error codes before the API contract review.

## Timing, audit and failure constraints

- Delegation expires within **15 minutes**. Renewal requires current eligibility;
  onward delegation and delegated-to-autonomous fallback remain disabled.
- Recheck authorization at execution admission for queued, retried or resumed
  work. A decision older than **30 seconds** cannot be reused; a newer decision
  does not excuse ignoring known changes to any applicable condition.
- Revocation acceptance and effective completion are distinct. Complete within
  **60 seconds**; subsequent admissions deny affected work, including existing
  sessions and queues. Already admitted work may finish with accurate outcome
  evidence. Do not claim revocation undoes external effects.
- Record all invocation decisions and security administration changes, with
  policy/contract versions, verified identity attribution, minimal resource facts,
  delegation reference and useful reviewer reasons. Retain audit for **90 days**;
  scope inspection/export, protect records and define controlled auditable expiry.
- Failure to persist durable admission evidence prevents dispatch. Identity,
  policy, ownership or credential verification failures also prevent dispatch.
  Audit failure cannot be hidden by success logs; recovered dependencies require
  fresh authorization rather than automatically releasing previously denied work.
- Bind downstream credentials to the organization/tenant/application/human,
  intended provider and purpose. Never pass through the incoming credential or
  fall back to a broader shared account; limit disclosed identity and response data.
- Last valid configuration may support temporary outages only within a design
  that meets revocation and evidence bounds. Expired/unverifiable authority fails
  closed. The long-term availability goal does not override these requirements.

For later UC-02, retain the **10-minute**, independent, exact-action, single-use
approval requirement. Do not reuse the foundation's automatic allocation retries
for approved mutations with uncertain outcomes. This slice enables no such action.

## Verification plan

Use independently controlled identity, audit and business-service fixtures on
random ports; protocol checks use a running Boot server and compatible SDK client.
No live coordinator/payment mutation, external provider call or service restart
is needed. Keep time-bound tests deterministic where possible, test at/across the
specified boundaries and bound all waits.

Fixtures include two organizations, two tenants within one organization, at least
two applications and differently entitled humans. Provision distinct authority
subjects and resources beyond the example names. UC-03 and third-party-positive
fixtures arrive in later increments; UC-01 tests their disabled paths.

Prove a permitted read has exactly one requested-tool execution and the allowed
output. Every denial/unavailable case has zero requested-tool executions and zero
protected output. Evidence lookups are counted separately from tool execution.
Include invalid/expired/wrong-audience identity; unknown/inactive applications;
missing/expired delegation; conflicting app/user grants; tenant/resource denial;
unpublished/hidden/unclassified tools; missing membership/unapproved contract;
audit outage; revoked/queued work; and unsafe credential/destination selection.

Test identity/email stability, administrative separation, no self-activation,
fresh policy after a session call and concurrent caller isolation. Plan v1.4's
100 interleaved calls across 50 users, two applications and two tenants without
claiming production throughput. Add scoped audit-read and retention-boundary
tests; no real files or records are deleted as part of this documentation task.

Record per-requirement evidence and exact test counts in WORKLOG. Keep tracker
rows pending until all parts pass; use partial when a test covers only UC-01's
part of a broader acceptance row. After Java/configuration implementation, run:

```powershell
.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' verify
```

Preserve existing baseline tests and running demos. Fixture-based verification
must be labeled as such; it does not establish live identity-provider integration.

## Existing code and remaining choices

Reuse evidence and patterns from the [secured foundation](SECURED_MCP.md): JWT
validation, SDK-owned protocol handling, trusted asynchronous context, Origin and
negative-counter tests. Existing Caller lacks separate app/human/delegation and
organization facts; TenantPolicy is immutable and specific to coordinator tools;
shared discovery and terminal logs do not meet enterprise filtering/durable audit.
These are extension points, not already verified v1.4 capabilities.

v1.4 selects behavior, not vendors or token formats. ADR 0009 records this
increment's proof mapping, persistence, publication consistency, audit recovery,
local revocation, failure responses and pinned protocol scope. Keep the Java/Boot/AI/BOM baseline;
do not install Keycloak, OPA/Cedar, databases, Kubernetes or upgrade protocol/SDK
solely from this plan. Any selected dependency change needs scoped compatibility
evidence and an architecture decision. Architecture approval is separate from
this accepted sequencing record.

## Later increments and release gate

After UC-01, deliver UC-03 ordinary workload reads and mixed-context isolation,
then UC-02 approvals and remaining internal/approved-third-party requirements.
Expand revocation and audit evidence to every newly supported principal, approval
and provider type. Sensitive reads follow sensitive-action rules; third-party
and autonomous sensitive actions remain disabled under v1.4 defaults.

The full identity release gate is all baseline MUST behavior plus IA-AC-01 through
IA-AC-32. Neither passing UC-01 tests, existing foundation tests nor a requirements
document marks that release complete. Broader enterprise E1-E4 remain separate
targets with their own evidence.
