# ADR 0009: Local UC-01 admission and bounded verified policy

Date: 2026-10-08. Status: selected for the first independently tested increment.

The owner requested implementing checklist items 1-4 and supplied catalog v1.3
in conversation. v1.4 supplies the 60-second revocation objective and separate
application/human authority. This increment retains the existing REST adapter,
Java/Boot/AI/SDK baseline and loopback endpoint; native federation remains later.

Use opt-in `uc01` plus an enabled secured tool profile. An approved issuer signs
resource-targeted at+jwt access tokens containing separate application, human
subject, organization, tenant, execution mode and delegation reference. These
claims identify trusted context; current lifecycle, grants and delegation come
from the authoritative policy. No request metadata supplies identity. Local
issuer fixtures prove behavior, not a deployed enterprise identity provider.
Control-plane console tokens use `ADMINISTRATION` mode, explicit admin/audit scopes,
an approved administration application and current tenant-scoped human roles.
They do not depend on a business delegation and cannot invoke business tools.

Use a private single-writer append-only policy journal and a separate forced
audit journal. Explicit trusted provisioning seeds an empty journal once and is
audited. Administrative changes and admissions serialize on the store: durable
decision evidence precedes dispatch, and a successfully committed revocation is
effective synchronously. Restart reads committed policy, never re-seeds grants.
File operations run on Reactor boundedElastic, outside Netty request threads.
Journal files are bounded; exhaustion or I/O failures fail closed. This is local
process durability and isolation, not distributed persistence or HA.

A policy read verifies the current authoritative journal. If temporarily
unavailable, retain the last verified policy for at most 30 seconds by monotonic
time; reads/failures of cached data never renew freshness. Invalid, rolled-back
or conflicting policy fails closed. Audit unavailability always prevents dispatch.
No authorization decision is cached. Known administrative changes invalidate
the local cached revision immediately; surviving a source outage cannot defeat
the 60-second revocation window. Future distributed publication needs its own
trusted version/freshness and acknowledgment protocol.
Administrative commits require an authoritative read, never fallback policy.

Contracts are explicit versioned schema pairs pinned in application catalogs;
registered/published/entitled/discoverable/invocable states are separate. New
versions never replace a membership. Compare the approved schemas with the
immutable running tool definition; unavailable/mismatched versions are excluded
and cannot execute. Only classified internal ordinary GET reads are enabled.
An owner explicitly requests ordinary membership; sensitive memberships remain
pending/disabled until the later approval implementation. Publication never
creates membership, and publisher privileges do not grant security privileges.

The pinned SDK has no caller-aware tools/list callback. Decorate its public
transport/session factory: delegate SDK initialization and all ordinary protocol
methods, and dispatch tools/list through an SDK session with a typed filtered
ListToolsResult handler. SDK sessions create envelopes/errors and negotiate the
protocol. No JSON-RPC controller, raw message construction, reflective access or
shared per-user tool-list mutation is used. Validate this through real clients.

Each admitted read selects a separate credential reference for the exact
organization/tenant/application/human/server/purpose context; missing credentials
deny execution. Use the existing bounded WebClient with explicit supplied token
and no application retry for this increment. Existing payment retry behavior is
unchanged outside uc01; all mutations are denied within uc01.
Each server approves its exact origin. The contract fingerprint includes public
metadata and private REST mapping, so unchanged schemas cannot conceal a changed
binding. Only one catalog/environment is accepted per application identity; use
distinct applications across environments. Ambiguous eligible versions fail closed.
Verified resource ownership is independent of app/human grants and binds tenant,
server and contract. Missing ownership fails verification; foreign ownership denies.

Audit export is authorized and tenant-scoped, with 90-day visibility/retention
metadata. Physical expiry/archival and power-loss durability are separate limits
until proven. Keep full baseline criteria partial/pending where later approval,
autonomous, provider or retention behavior remains unimplemented.
