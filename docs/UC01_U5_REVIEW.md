# UC-01/U5 continuation review

Date: 2026-10-18. Reviewed baseline: `b835413`, followed by the scoped pagination
correction in [ADR 0013](decisions/0013-audit-export-append-boundary.md).
This is a focused source/evidence review, not full enterprise acceptance.

## Corrected finding

**P2 — Audit exports could grow through their own access records.**
[AdmissionService.auditPage](../src/main/java/dev/mcp/gateway/admission/AdmissionService.java)
originally limited records by `at <= snapshotAt` and located the preceding record
by hash. With a fixed clock, later `AUDIT_ACCESS` records share the snapshot time.
A deterministic isolated probe exported two fixture records followed by four new
access records; its cursor still had not terminated after six pages. A backward
clock also rejected an otherwise valid continuation because its saved snapshot
appeared to be in the future. Later backdated records could enter the export too.

The correction freezes a scoped append boundary and position in the opaque
cursor. It preserves HTTP fields and journal bytes. Current reviewer permission,
scope, exact retention expiry and durable access auditing remain required.
In-progress cursors from the previous binary must restart export. Cursor hashes
check consistency and do not provide signed authority.

[AdmissionBoundaryTest](../src/test/java/dev/mcp/gateway/admission/AdmissionBoundaryTest.java)
adds repeated/backward-time, cursor consistency, reopened-journal, current expiry,
reviewer revocation and independently authorized cross-tenant cursor tests.
[Uc01AdmissionIT](../src/test/java/dev/mcp/gateway/admission/Uc01AdmissionIT.java)
extends real HTTP pagination after SDK execution with a later backdated record
and a missing-snapshot rejection. The original regression failed before the fix;
the exact commands and final totals are in the latest [WORKLOG](../WORKLOG.md).

## Remaining U5 boundaries

| Area | Current evidence | Remaining implementation or proof |
| --- | --- | --- |
| Retention and storage lifecycle | Ninety-day visibility cutoff; forced retained rollover; restart, corruption and finite capacity tests | Controlled physical expiry/off-device archival, approved checkpoint/restore semantics, lifecycle auditing and failure recovery. No purge is implemented; capacity still fails closed. |
| Topology and memberships | Policy can represent multiple tenants; request context selects one; local admission checks current organization/tenant status | Administrative changes cannot generally manage organization/tenant lifecycle or remove one membership from a multi-tenant human. HUMAN changes intentionally require a single tenant; this is bounded U2 administration, not full topology administration. |
| Supported revocation | HTTP administration plus existing SDK sessions cover human/app/server/publication/grant changes; delegation/resource/credential and deferred-subscription tests supplement them | A bounded queue supplier test and SDK revocation tests are separate evidence. Complete composed SDK queue/revocation timing under resource saturation is not established. Workloads/approvals/providers need their own positive and revocation matrices when enabled. |
| Audit inspection | Scoped bounded pages; current role checks, retention and reopen evidence; sanitized early HTTP rejection audit | No production search/archive service, off-device storage, general content policy or full approval/provider evidence. Timestamp filtering remains a visibility condition; append position fixes membership of a page sequence. |
| Recovery and distributed behavior | Single-writer locks; digest-linked contiguous segments; no business replay; unresolved outcomes recovered as unknown | Whole-backup rollback, older-binary downgrade, power-loss/directory-entry durability, clustered state/session routing and distributed revocation remain unproven. |
| Resource cost | One disk worker, bounded queue/wait and retained journal budgets; no unbounded page response | Page reads still scan retained history, and current policy verification reads/hashes current bytes. No production throughput, fairness, latency or HA target is established. |

The applicable source obligations include identity OP-01–06, AU-09, EX-01 and
IA-AC-21–25/31, plus catalog LIFE-002/004/006/008. See the preserved
[identity requirements](requirements/Enterprise_MCP_Identity_Authorization_Requirements_v1.4.md),
[catalog requirements](requirements/Enterprise_MCP_Discovery_Catalog_Requirements_v1.3.md)
and their [identity](IDENTITY_ACCEPTANCE.md)/[catalog](CATALOG_ACCEPTANCE.md) trackers.
No full-baseline row is promoted by this review or correction.

## Next bounded choices

U5 remains **Partial**. Physical retention needs a separate operational contract
for archive/checkpoint ownership, audit preservation, authorized expiry and
recovery before implementation. The standing no-delete rule still applies to
any actual file removal; a retention timestamp is not deletion permission.

UC-03 ordinary workload reads remain the next proposed caller capability after
this review. They require their own selected scope, design and acceptance mapping:
distinct workload identity/owner/application, no fabricated human or mode fallback,
grant intersection, exact credentials, durable audit and mixed-context isolation.
This correction enables no workload, sensitive-action or third-party execution.
