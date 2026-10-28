# S2 functional requirements: audit lifecycle and recovery

Version: 0.2. Date: 2026-10-28. Status: requirements retained; bounded local
checkpoint/recovery implementation under [the operational contract](S2_STORAGE_CONTRACT.md)
and [ADR 0016](decisions/0016-offline-audit-checkpoint-and-restore.md).
Owner contract review before S4 and physical-expiry acceptance remain outstanding.

## Purpose and authority

Make the local gateway's security evidence recoverable and its finite storage
operable without losing retained records, restoring revoked authority or replaying
business work. This document refines S2 in the [sprint roadmap](GATEWAY_SPRINT_ROADMAP.md).
It supplements the completed [foundation specification](FUNCTIONAL_SPEC.md);
it does not replace the owner-supplied identity/catalog requirement sources.

Identity v1.4 OP-03 through OP-08 and IA-AC-23/24/25/31 govern attribution,
retention, durable admission and recovery. Read the preserved
[identity requirements](requirements/Enterprise_MCP_Identity_Authorization_Requirements_v1.4.md),
[identity acceptance](IDENTITY_ACCEPTANCE.md), and
[local journal decision](decisions/0012-local-admission-hardening.md).
S2 identifiers below are local refinements, not new source acceptance IDs.
Implementation evidence is recorded separately in
[the S2 recovery report](S2_RECOVERY_EVIDENCE.md); requirement text alone does
not establish acceptance.

## Existing behavior and boundaries

[S1](S1_LIFECYCLE_EVIDENCE.md) is complete for local delegated ordinary reads:
authorized lifecycle/selective memberships, stable authority/subject identity,
versioned timing defaults and composed queued revocation. Its historical Wrapper
verification at commit `cdd9af4` passed 144 unit/startup and 60 integration tests.
The subsequent S2 increment at `5e07d2f` records 152 unit/startup and 62 integration
tests, zero failures/errors/skips, in 4:03 under `target/s2-reviewed`.
This documentation update does not rerun or extend those results.

Current journals have an exclusive writer, digest-linked retained segments,
finite storage budgets and forced admission evidence before dispatch. Audit
export has scoped append-boundary pagination and a 90-day visibility cutoff.
Restart records unresolved admissions as unknown without replay. These facts do
not establish off-device archival, safe physical expiry, whole-backup rollback
protection or production recovery objectives. U5 and full release remain Partial.

Keep Java 21, Boot 4.0.8, Spring AI BOM 2.0.1, SDK 2.0.0 and the tested MCP
2025-11-25 runtime. Modern migration stays deferred under ADR 0014. S2 adds no
workload/provider/sensitive-action execution, new MCP capabilities, clustered
control plane, deployment or live coordinator operations.

## Required product behavior

These requirements define the full S2 completion gate. The local non-destructive
choices are selected below and implemented under ADR 0016; physical expiry and
the remaining operational qualification are incomplete. The thirteen requirements
and their identifiers retain their original meaning.

| ID | Functional requirement |
| --- | --- |
| S2-FR-01 | Lifecycle operations must require explicit scoped authority, distinct from ordinary invocation and audit viewing. Cross-tenant or cross-organization operations must require explicit authority for every included scope. Initial/operator recovery authority must be documented; restore must not depend solely on permissions supplied by the backup being restored. |
| S2-FR-02 | Archive, checkpoint, restore and any future expiry must have an operation ID, authorized actor, reason, scope, accepted time, terminal status and effective time where applicable. Durable lifecycle evidence must distinguish prepared, completed, failed and interrupted operations. A failed acknowledgment after a durable commit must remain inspectable rather than invite blind replay. |
| S2-FR-03 | A checkpoint must identify a consistent policy revision and audit append boundary, complete required segments, format/software compatibility, ordering, record counts and integrity digests. Its manifest must preserve the relationship between policy changes, admission decisions and unresolved outcomes. A partial checkpoint must not be advertised as restorable. |
| S2-FR-04 | Archive completion requires verification that the selected destination holds the complete checkpoint and manifest and that their integrity can be checked independently. Local rollover or a successful export response alone must not be reported as independent archival. An unavailable destination must not produce a success claim. |
| S2-FR-05 | Preserve at least the required 90 days of evidence and the policy/history needed to interpret it. Unresolved outcomes and evidence dependencies must not be discarded merely because a timestamp expires. Wall-clock changes must not cause premature expiry. Visibility filtering must remain distinct from physical retention. |
| S2-FR-06 | Restore must verify completeness, digests, ordering, format compatibility and supported policy freshness before enabling business admission. Corrupt, missing, reordered, incompatible or unverifiably old input must fail closed with an inspectable recovery failure. A digest alone must not be treated as protection against whole-backup rollback. |
| S2-FR-07 | Restoring an older internally valid checkpoint must not reactivate revoked identity, membership, grants, delegation, publication or credentials. Define a trusted freshness/rollback check outside the restored evidence, or keep admission disabled when current authority cannot be established. An operator override must not silently turn stale policy into current authority. |
| S2-FR-08 | Recovery must not replay admitted business work or convert uncertain outcomes into success/rollback. Preserve original IDs, policy revisions and attribution. New, queued, retried or resumed work requires fresh authorization and durable admission evidence; recovery does not renew policy fallback or delegation expiry. |
| S2-FR-09 | Define a single-writer quiescence/fencing procedure for checkpoint and restore. Concurrent writes must either belong to a documented consistent boundary or be prevented. Interruption at each publication/commit step must leave a verifiable state that can be resumed or rejected safely without manual guessing. |
| S2-FR-10 | Provide authorized capacity status covering retained bytes/segments, configured limits, archive/checkpoint status and unresolved work. Define warning/critical thresholds and growth/runway measurement, including sample window, insufficient-history behavior and assumptions. These are operational signals, not a promise of indefinite storage. |
| S2-FR-11 | Full storage, denied filesystem access or audit-write failure must prevent new dispatch whenever required admission evidence cannot persist. Status must distinguish read-only inspection from execution readiness. Recovery must revalidate storage, integrity and authority before resuming; it must not automatically release previously denied calls. |
| S2-FR-12 | Lifecycle output, manifests and diagnostic logs must omit secrets and unnecessary protected payloads. Existing scoped audit access, stable pagination and secret-handling boundaries must remain. A checkpoint containing multiple scopes is privileged recovery material, not an ordinary tenant export. |
| S2-FR-13 | Physical expiry must remain unavailable until its retention/dependency contract, authorization, auditable completion and interruption behavior are defined. This requirements document grants no file deletion permission. Archive creation must not imply removal of local evidence. |

## Acceptance matrix

States below reflect the bounded local evidence supplement; Partial does not
establish the full operational/release gate. Use independent local fixtures, random ports, bounded
waits and exact counters. Storage faults may be injected or simulated without
removing files. Any actual file/directory deletion requires explicit permission.

| ID | Coverage | Required observable evidence | State |
| --- | --- | --- | --- |
| S2-AC-01 | FR-01/02/12 | Authorized lifecycle action records scoped actor/reason/before-after boundaries; ordinary caller, audit viewer and foreign-scope actor cannot perform it or obtain protected material. | Partial |
| S2-AC-02 | FR-03/09 | Checkpoint during attempted concurrent admissions has a provable consistent policy/audit boundary and complete manifest; no admitted call lacks durable evidence. | Partial |
| S2-AC-03 | FR-02/03/04/09 | Interrupt every archive/checkpoint publication step; partial copies never report completion; unavailable archive fails visibly; retry/restart does not overwrite committed evidence or create ambiguous completion. | Partial |
| S2-AC-04 | FR-03/06/08 | Restore a valid complete checkpoint into a controlled stopped-writer fixture; original records/IDs and unresolved outcomes survive; no business work is replayed. | Partial |
| S2-AC-05 | FR-06/07 | Tampered, truncated, incomplete, reordered and incompatible checkpoints deny readiness. A complete but older valid backup cannot undo a later revocation; missing freshness authority keeps admission disabled. | Partial |
| S2-AC-06 | FR-05/13 | Test just before, at and after retention boundaries, backward clocks and unresolved evidence dependencies. Visibility expiry does not remove files. Physical-expiry acceptance remains pending until its separate operational/deletion gate is met. | Partial |
| S2-AC-07 | FR-10/11 | Simulated full storage and write/access failures prevent new requested-tool execution and protected output. Capacity signals cross specified thresholds; insufficient history does not fabricate a runway estimate. | Partial |
| S2-AC-08 | FR-07/08/11 | After interrupted restore or storage recovery, stale/revoked/expired authority still denies; a newly authorized SDK call succeeds only after fresh checks and durable audit, with exact downstream counts. | Partial |
| S2-AC-09 | FR-09/12 | A second writer/unsafe restore is rejected; lifecycle operations preserve tenant-scoped pagination and existing failure sanitization. | Partial |
| S2-AC-10 | All applicable | Full product Wrapper verify passes after implementation, including existing foundation/S1 Boot/SDK checks; record commands, timings, counters and remaining operational limits. | Verified (local regression) |

## Selected local decisions and remaining operational work

[ADR 0016](decisions/0016-offline-audit-checkpoint-and-restore.md) and
[the storage contract](S2_STORAGE_CONTRACT.md) select these choices for the
implemented increment. Owner contract review before S4 remains outstanding.

| Decision area | Selected local contract | Remaining obligation |
| --- | --- | --- |
| Archive destination and integrity | New private directory outside the source, optionally on a mounted off-device volume; exact ordered manifest, record counts, SHA-256, read-back verification and independently retained receipt. Destination failure fails the command. | Provision and demonstrate independent durability/access controls; no managed remote archive service or adversarial-OS protection. |
| Lifecycle authority and surface | OS-authorized all-scope storage operator, private checkpoint/verify/restore/status CLI, actor/ticket references, prepared/terminal audit evidence and external restore intent. No maintenance access follows from tenant audit viewing. | Deployed account attribution, multi-operator governance and external incident handling when failure evidence cannot persist. |
| Checkpoint and restore fencing | Stop/fence the gateway; acquire exclusive journal locks; publish completion last; use new destinations and reject incomplete or missing restored history. Manifest format is gateway-uc01-segmented-s2-v1. | Power-loss directory-entry durability, clustered fencing and binary downgrade migration are unproven. |
| Recovery freshness | Independently trusted digest/revision plus fresh external cold-start authorization, matching receipt and minimum current policy revision, with at most a 30-second validity interval. Missing/stale/conflicting authority denies startup. | Operators must reconcile later revocations and post-checkpoint evidence. No distributed current-authority or rollback oracle is supplied. |
| Retention and uncertain outcomes | Preserve every retained record, including expired-visibility evidence and unfinished outcomes. Restart marks unfinished admissions unknown without replay. No physical expiry or capacity reclamation. | Separate expiry/hold/dependency policy, auditable interruption handling and explicit deletion permission before any removal. |
| Capacity and recovery objectives | WARNING at 80%, CRITICAL at 95% or below one segment of usable space; rates sampled externally over at least 15 minutes; zero/missing rate yields unknown runway. Full audit storage denies dispatch. | External alert routing, failure-audit reserve and production capacity/RTO/RPO remain unselected or unproven. |

## Delivery and release gate

The local non-destructive storage contract is implemented with named evidence
below. Review that contract before selecting S4; this documentation update does
not record owner approval. Remaining physical-expiry and recovery obligations stay
explicitly incomplete. The implemented checkpoint/recovery increment does not
establish full S2 operational qualification or production release readiness.
Keep [identity](IDENTITY_ACCEPTANCE.md) and [catalog](CATALOG_ACCEPTANCE.md) states
unchanged until their complete applicable obligations have evidence. S4 workload
identities remains a separate proposed slice after S2's storage contract review.

## Bounded local S2 evidence supplement

See [the storage contract](S2_STORAGE_CONTRACT.md) for ownership, failure handling,
capacity sampling and executable commands. `AuditCheckpointTest` exercises actual
files with injected partial writes (four checkpoint publication writes and five
restore publication writes), unavailable destinations, writer exclusion,
wrong receipt/revision, corruption, expired/missing independent authority, retained
old evidence, segmented restore/reopen, refusal to bootstrap missing restored
base history and capacity projection.

`Uc01AdmissionIT.verifiedCheckpointRestorePreservesRevocationAndUnknownOutcomeWithoutReplay`
restores a revoked policy and synthetic unresolved admission, then starts a real
Boot server/SDK clients: no recovery dispatch, revoked call denied, one fresh
authorized call makes exactly one backend request; second restart adds no duplicate
unknown outcome. `exhaustedAuditStorageDeniesActualSdkDispatchAndSurvivesRestart`
fills a bounded fixture journal, observes CRITICAL, denies a real SDK call with
zero downstream requests/output and preserves the records across restart.

| Rows supplemented | Local evidence | Remaining full-scope limit |
| --- | --- | --- |
| S2-AC-01/09 | Private OS-authorized offline command, required actor/ticket, all-scope lifecycle records, source locks; no caller-facing route | Deployed OS ACL/authority mapping, multi-operator governance and broader scope matrices are not proven |
| S2-AC-02/03 | Both journals captured under locks/monitor; ordered bounded manifest; partial writes at every file publication step fail, source reopens; unavailable archive fails | Power-loss/directory durability and independent off-device storage are not proven |
| S2-AC-04/08 | Segmented copy/restore/reopen plus actual Boot/SDK revocation, uncertainty and fresh authorized call | Synthetic unresolved fixture supplements earlier lost-outcome tests; production RPO/RTO remain unselected |
| S2-AC-05 | Digest/revision/integrity checks; fresh external authorization required at cold start; a higher independent revision blocks an old valid checkpoint | Current-authority attestation is trusted operator input, not a distributed rollback oracle; adversarial OS and all format permutations are unproven |
| S2-AC-06 | Entire retained history, including old unresolved records, copied without expiry; existing deterministic visibility boundary evidence retained | Physical expiry/holds/dependency purge remain unavailable and need separate permission |
| S2-AC-07 | Partial ENOSPC simulation, unavailable destination, full source journal, WARNING/CRITICAL and explicit-rate runway | Host disk not filled; offline sampling/notification routing and production capacity are operator responsibilities |
| S2-AC-10 | Final Wrapper command/results are recorded in WORKLOG and the S2 evidence report | Full S2/release completion is not inferred from regression checks |
