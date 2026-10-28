# ADR 0015: UC-01 lifecycle and reserved control capacity

Date: 2026-10-28. Status: selected for S1 under the owner-requested handover.

## Decision

Retain Java 21, Boot 4.0.8, Spring AI BOM 2.0.1, SDK 2.0.0 and the tested
2025-11-25 protocol. Modern migration remains deferred under ADR 0014. Extend
only local delegated ordinary reads and their administration.

Require ADMINISTRATION mode, an approved console application, gateway:admin,
current active human/tenant/application context and SECURITY_ADMIN. Organization
and tenant lifecycle additionally requires the explicit ORGANIZATION_ADMIN role
in the selected tenant of the organization. Organization operations target the
caller's existing organization; new organizations still require trusted private
provisioning. Tenants can be enrolled only PENDING in that organization. Neither
identifier can be reparented or reused after RETIRED. Other valid lifecycle states
are ACTIVE and SUSPENDED. No deletion operation is added.

For organization lifecycle only, permit the organization's PENDING/SUSPENDED
status during administration so a still-active authorized console/human/tenant
can restore it. This exception grants no business execution, other control action
or access to a retired organization. Keep an active administration tenant for
recovery; suspending all console tenants requires trusted operational recovery,
not an authentication bypass. Tenant administration may target another tenant
in the same organization through explicit organization authority.

HUMAN_MEMBERSHIP and APPLICATION_MEMBERSHIP add/remove exactly the selected
request tenant. Preserve all other memberships; removing a human membership also
removes only that tenant's roles, so re-adding membership cannot restore roles.
No grants, delegations, resource scopes, credentials or catalog entitlements are
created. Deny self-membership changes and changes to the console application or
an application owned by the administrator. Whole-application updates also check
both the current and proposed owner, so transferring ownership in the same
request cannot bypass the self-activation restriction. Whole-principal HUMAN/APPLICATION
updates preserve membership sets and require SECURITY_ADMIN in every existing
membership; stable authority/subject and retired identity checks remain.

Add immutable snapshot-wide timing with defaults policyMaxAgeSeconds=30 and
delegationMaxSeconds=900. Values must be positive and cannot exceed those bounds.
TIMING changes require both SECURITY_ADMIN and ORGANIZATION_ADMIN explicitly in
every tenant represented in the snapshot, including other organizations. A local
tenant administrator cannot alter global defaults. Trusted provisioning can
establish that authority; there is no public platform-admin enrollment. Policy
revision, attributable before/after audit and optimistic expectedRevision govern
timing changes just like other administration.

Fallback age is the smaller of the deployment maximum and the last verified
snapshot's policyMaxAgeSeconds, measured monotonically from successful source
verification. Reads/failures do not renew it. delegationMaxSeconds constrains new
administrative issuances, including renewals using a new ID; existing explicit
issuedAt/expiresAt and grants keep their meaning. Hard baseline limits remain:
15-minute delegation ceiling, 60-second revocation objective and 90-day audit
retention. Approval/mutation/provider/workload defaults stay disabled. This adds
no API for enabling them or changing retention. Missing timing in legacy policy
snapshots resolves to the original defaults. New snapshots retain timing through
all copy/update paths and journal restart. Older binaries cannot read the new
field; binary downgrade against this directory needs a separate migration.

Reserve one bounded control worker with 16 waiting tasks and a five-second queue
budget, separately from the business/admission worker with 256 waiting tasks and
the same budget. Commits still serialize with admission on the journal monitor;
forced admission audit precedes dispatch. Control rejection audit and business
queue-failure evidence use reserved control capacity. If evidence cannot persist,
execution stays denied. This is bounded local capacity, not priority over a disk
write already underway, tenant fairness, or an OS I/O deadline. Disk stalls and
control saturation can prevent completion; callers receive no successful
completion acknowledgment for failed/unavailable work.

Capture call ID and queuedAt before business queue residence; record the current
policy revision and decision at admission. Capture acceptedAt when administration
is subscribed, before its control queue. Return changeId, acceptedAt, effectiveAt
and committed revision only after prepared audit, forced policy commit and forced
effective audit. An effective marker identifies the committed local revision;
a failed response after commit remains uncertain and must be inspected rather
than automatically replayed. Already admitted work may finish with its original
attribution; revocation does not undo downstream effects.

## Evidence and limits

See [S1 evidence](../S1_LIFECYCLE_EVIDENCE.md) for named real Boot/SDK tests,
queue saturation, revocation timing, zero downstream counts and expiry checks.
The queue is held before journal acquisition, then actual subscribed MCP calls
wait and administrative HTTP changes commit. This composes the real request
path with deterministic local admission contention; it is not a disk-fault or
throughput benchmark. Keep U5 and release-wide identity/catalog rows Partial
where physical retention, recovery and later execution modes remain outstanding.
