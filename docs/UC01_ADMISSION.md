# Local UC-01 identity and admission

This opt-in increment implements human-delegated internal ordinary reads on the
existing REST adapter. [ADR 0009](decisions/0009-uc01-local-admission-design.md)
records the implementation choices. [Identity v1.4](requirements/Enterprise_MCP_Identity_Authorization_Requirements_v1.4.md)
and [catalog v1.3](requirements/Enterprise_MCP_Discovery_Catalog_Requirements_v1.3.md)
remain the behavioral baselines. Full first-release completion still requires
all 32 identity criteria; the [identity](IDENTITY_ACCEPTANCE.md) and
[catalog](CATALOG_ACCEPTANCE.md) trackers distinguish tested scope from later work.

## Checklist implemented in this increment

1. Separate authority-scoped humans and approved applications, active organization
   and tenant membership, lifecycle checks, trusted initial provisioning and scoped
   security administration. Owners cannot self-activate or self-grant.
2. Approved internal server/origin, classified published contracts, explicit
   application catalog membership, immutable contract fingerprint, independent
   discovery and invocation eligibility. Publication creates no membership.
3. Application and human grants intersect with current delegation and exact
   tenant/resource grants. Select an explicit context credential and persist
   admission evidence before one requested read. Project only approved output fields.
4. Fresh admission on every subscription, effective local revocation, bounded
   verified-policy fallback and durable pending/outcome records. Restart preserves
   policy and records unfinished admissions as uncertain without replay.

## Private configuration and identity contract

Run `workers,secured,uc01` together. Default startup and existing demo profiles do
not enable this behavior. Configure a real approved issuer and its fixed JWKS URL;
tests alone use an ephemeral loopback issuer. No authentication fallback, built-in
login, token exchange, Keycloak installation or live IdP integration is supplied.

The caller access token is RS256 `at+jwt`, with verified issuer, exact configured
gateway audience, `iat`, `exp`, subject, tenant and space-separated scopes. The
UC-01 issuer also signs `application`, `organization`, `execution_mode` equal to
`HUMAN_DELEGATED`, `delegation` and `agent_run`. Subject plus authority is the human
identity; display names never grant access. Application identity is an issuer
assertion, then independently checked against current application approval.
`agent_run` is attribution only. Arguments and `_meta` cannot supply authority.

Use a private Boot YAML configuration for secrets and paths, for example:

```yaml
gateway-security:
  issuer: https://identity.example.invalid/realms/enterprise
  jwk-set-uri: https://identity.example.invalid/realms/enterprise/protocol/openid-connect/certs
  resource: https://gateway.example.invalid/worker-coordinator/mcp
  policy-location: file:C:/private/mcp/bootstrap.json
admission:
  bootstrap: C:/private/mcp/bootstrap.json
  journal-directory: C:/private/mcp/journals
  policy-max-age: 30s
  credentials:
    reader-context:
      token: ${UC01_DOWNSTREAM_TOKEN}
      audience: https://coordinator.example.invalid
      expires-at: ${UC01_DOWNSTREAM_EXPIRY}
gateway:
  backends:
    worker-coordinator:
      base-url: https://coordinator.example.invalid
```

These `.invalid` addresses are explanatory placeholders. Set the values for the
approved deployment. The gateway remains bound to loopback; a public resource
address does not configure TLS or an edge proxy. Shared legacy backend bearer
tokens are ignored by UC-01. The retained `gateway-security.policy-location`
setting satisfies the existing security settings contract; the UC-01 authoritative
policy is its own journal, initialized from `admission.bootstrap`.

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=workers,secured,uc01' '-Dspring-boot.run.arguments=--spring.config.additional-location=file:C:/private/mcp/uc01.yml'
```

Do not put access tokens on command lines or in tracked files. Boot does not load
`.env` automatically. The synthetic integration fixtures create private bootstrap
and journal files under ignored `target/uc01-fixtures`; those are test artifacts.

## Policy model and provisioning

[AdmissionPolicy](../src/main/java/dev/mcp/gateway/admission/AdmissionPolicy.java)
defines the JSON records. Top-level fields are `revision`, `organizations`,
`tenants`, `humans`, `applications`, `servers`, `capabilities`, `catalogs`,
`delegations`, `grants`, `credentials`, and `resources`. IDs are explicit map keys, independent
of display names. Humans can have explicitly approved multiple-tenant memberships;
no inheritance across tenants is assumed.

Each application identity has one catalog/environment in this increment. Use
separate application identities for different environments. Multiple catalogs
for the same application are rejected rather than merged. Catalogs have owners;
memberships identify capability, version, status, requester and purpose.

Server records approve organization, owner, trust, status, backend reference and
exact origin. Capabilities bind a tool, version, classification, resource argument,
input/output schemas and SHA-256 contract fingerprint. The
[fingerprint implementation](../src/main/java/dev/mcp/gateway/admission/ContractFingerprint.java)
canonicalizes public semantics and private REST mapping. Changed schemas,
descriptions, annotations or bindings require an explicit new contract. Missing,
mismatched or ambiguously approved versions cannot execute. Only GET bindings
whose path maps exactly the authorized resource argument are eligible.

Each read needs two explicit `READ` grants, one `APPLICATION` and one `HUMAN`,
matching tenant, capability, contract version and exact resource identifier.
An independent `ResourceScope` binds organization, tenant, server, capability,
version and resource. Missing ownership fails verification; ownership in another
tenant is denied. Permission records alone never establish resource ownership.
Delegation binds organization, tenant, human, application, allowed capabilities
and resources; it lasts at most 15 minutes. Each invocation rechecks its current
status and expiry. Unsupported policy conditions and classifications are rejected.

Policy credential records contain references, never secrets. Each reference binds
organization, tenant, application, human, server and `READ` purpose. Runtime
credentials must have the exact approved backend audience and remain unexpired.
Absence, ambiguous context, revocation or audience mismatch prevents dispatch;
there is no broader shared-account fallback. Deployment-provisioned credentials
are trusted to have the declared least-privilege read authority; their issuer/scope
integration is not demonstrated by these local opaque-token fixtures.
If an upstream echoes a configured downstream secret in an otherwise allowed
output field, the projected response is rejected with no protected structured
content. This checks known configured secrets; it is not a general data-loss
prevention system for arbitrary sensitive business content.

Trusted initial provisioning is a private deployment operation. An empty policy
journal is initialized once, with durable provisioning evidence. On restart,
changing bootstrap text does not overwrite the committed journal. Protect journal
and configuration paths with OS permissions appropriate for the deployment; this
local implementation does not protect against an administrator controlling the OS.

## Administrative API

The control API is authenticated with the configured gateway audience. Explicit
administration application approval, current human membership, scopes and tenant
roles are separate requirements. There is no unauthenticated admin enrollment.
Console tokens use issuer-signed `execution_mode: ADMINISTRATION`; they need no
business delegation. Tool tokens use `HUMAN_DELEGATED` with current delegation.
Console authority cannot invoke tools or manufacture delegated tool authority.

`POST /control/changes` takes `expectedRevision`, `action`, the action-specific
fields below, and `reason`. Wrong revisions and unauthorized changes return 403;
validation returns sanitized errors. Unknown or irrelevant fields are rejected.
Success includes committed `revision`, `accepted: true` and `effective: true`.
This is synchronous local completion, not a clustered acknowledgment.

| Action | Role | Action fields |
| --- | --- | --- |
| HUMAN | SECURITY_ADMIN | target, human |
| APPLICATION | SECURITY_ADMIN | target, application |
| GRANT / REVOKE_GRANT | SECURITY_ADMIN | grant |
| DELEGATION | SECURITY_ADMIN | target, delegation |
| REVOKE_DELEGATION | SECURITY_ADMIN | target |
| SERVER | SECURITY_ADMIN | target, server |
| CONTRACT | SECURITY_ADMIN | target, contract |
| CREDENTIAL / REVOKE_CREDENTIAL | SECURITY_ADMIN | credential |
| RESOURCE / REVOKE_RESOURCE | SECURITY_ADMIN | resource |
| CATALOG | SECURITY_ADMIN | target, catalogDefinition |
| PUBLICATION | PUBLISHER | target, state |
| MEMBERSHIP | CATALOG_OWNER and accountable catalog owner | catalog, capability, version, state, purpose |

HUMAN/APPLICATION enrollment starts pending; a different authorized administrator
activates it. Authority/subject and retired principal IDs cannot be reassigned.
Identity administrative updates currently support principals with one tenant;
multiple-tenant provisioning is explicit bootstrap policy, with execution checks
for the selected tenant. Organization/tenant topology changes need a later scoped
administrative contract. New catalogs start empty. New contracts start pending;
classification is assigned by security administration, publication by publisher.

MEMBERSHIP state is `REQUEST` or `REMOVE`. A published ordinary read can activate
after its owner's explicit request. Sensitive requests stay `PENDING`; no sensitive
approval or execution API is enabled. Removing one application membership does
not change another application. A publisher has no implicit security grant or
permission to execute its published tools.

`GET /control/audit` requires `gateway:audit`, an administration application and
the tenant's `AUDIT_VIEWER` role. It records audit access and returns only that
organization/tenant's records within the 90-day visibility period. There is no
audit edit/delete route. Mutating control calls require `gateway:admin`. Origin
validation also protects control routes.

## Failure, timing and durability

Admission is serialized with local administration. Each decision obtains current
policy, checks eligibility and forces its audit record to disk before dispatch.
Work whose publisher has not yet subscribed is checked when subscribed. Already
admitted work may finish after revocation; revocation does not undo external effects.

Policy fallback uses monotonic time, at most 30 seconds from successful authority
verification. Cached reads and failed refreshes never renew it. Invalid, conflicting
or rolled-back authority invalidates cached permission immediately. Administrative
commits require an authoritative read and cannot write using fallback policy.
Audit failure always prevents a new dispatch, even inside the policy grace period.

The GET executor makes one application attempt with connector retries and redirects
disabled, inside the existing total deadline and response-size bounds. Protected
output is absent from errors. Outcomes after dispatch remain `unknown` if response
or outcome evidence is unavailable. Admission records remain durable; restart
marks unresolved work `UNKNOWN_AFTER_RESTART` and does not replay it.

`policy.jsonl` and `audit.jsonl` each have a 16 MiB limit and exclusive writer lock.
Policy revisions append and audit uses `FileChannel.force(true)`. Startup rejects
incomplete journals. There is no compaction, audit archival or controlled physical
expiry yet; capacity exhaustion fails closed. OS/power-loss durability, clustered
distribution and rollback recovery from backups are not proven. Keep the full
retention and release criteria pending rather than treating forced local writes as
a production audit platform.

## Executed verification and limits

`Uc01AdmissionIT` runs a real Boot server and compatible SDK clients against
independent issuer and REST mocks. `AdmissionBoundaryTest` adds deterministic
delegation/credential expiry and outcome-recovery checks. `VerifiedPolicyCacheTest`
proves freshness, rollback, restart and writer exclusion. The SDK continues to own
negotiation, envelopes, sessions and unknown tools; caller-specific `tools/list`
uses its public transport/session SPI rather than shared tool-list mutation.

This does not prove live IAM/provider integration, native MCP federation,
production throughput, high availability or stream recovery. Sessions remain the
pinned SDK's 2025-11-25 protocol model. Reissue discovery to obtain a fresh filtered
catalog; automatic catalog-change notifications and transparent recovery of a
broken filtered response stream are not provided. Workload reads, sensitive
approvals and approved third-party execution are subsequent increments.
