# S2 local audit storage operational contract

Date: 2026-10-28. Scope: non-destructive, offline, single-writer UC-01
checkpoint/archive and restore. Physical expiry is not implemented or authorized.
This resolves the bounded local choices in the
[S2 requirements](S2_AUDIT_LIFECYCLE_REQUIREMENTS.md); see [ADR 0016](decisions/0016-offline-audit-checkpoint-and-restore.md).
S4 remains gated on owner review of this contract. Runtime pins and deferred
modern MCP migration remain unchanged.

## Ownership and trust

The deployment storage operator owns journal volumes, archive destinations,
access controls, monitoring, recovery authorization and an independently retained
checkpoint receipt. This is a privileged private deployment operation, not an
ordinary caller or tenant audit-viewer capability. Actor and change-ticket
references are required; never supply tokens, personal details or free-form
business payload. OS access is the authorization boundary. No new HTTP route.
The actor is an operator-supplied accountability reference under that OS boundary,
not a JWT-verified caller identity. Deployment account/access records must establish
which operator actually ran the command.

Stop the gateway and fence every previous writer before maintenance. The tool
acquires the existing journal locks and never creates missing source journals.
Use a new private archive directory outside the source directory. Archive storage
may be a locally mounted off-device volume; S2 does not provision replication,
encryption, object storage, remote credentials or backup scheduling. The operator
must independently preserve receipts and verify copies after transport.

## Checkpoint and integrity

One checkpoint copies every policy/audit segment, including records older than
90 days and every admitted action and outcome. No filtering, compaction, replay,
rotation deletion or capacity reclamation occurs. The operator must preserve at
least 90 days of evidence and retain unresolved outcomes beyond that period until
reconciled under an approved policy. Archive access stays private and scoped
exports retain their existing permission and visibility rules.

An attributable CHECKPOINT_STARTED event is forced before copying. The writer
lock and journal monitor cover the complete snapshot. Versioned manifests bind
checkpoint ID, UTC time, actor/ticket, policy revision, storage bounds, exact file
ordered inventory, per-file record counts, byte counts, format compatibility ID
`gateway-uc01-segmented-s2-v1` and SHA-256 digests. The append boundary is the
complete ordered audit inventory and its record counts/digests. Every file is created exclusively,
forced and read back. A completion file containing the manifest digest is written
last. A receipt contains that digest and revision; retain it independently, away
from the archive and journal volume. A digest stored only beside its data is not
an independent trust anchor. These checks detect corruption, not malicious OS
administration. They do not prove power-loss/directory-entry durability.

Only a complete, verified checkpoint is restorable. An interrupted checkpoint
stays incomplete; do not append to or reuse its directory. Start a new checkpoint
after fixing capacity or availability. Source evidence stays authoritative.
CHECKPOINT_COMPLETED/FAILED evidence is appended when the source permits it;
if storage cannot persist failure evidence, the command fails and stderr plus
operator incident records supply the out-of-band alert. Never report success
when audit completion cannot be forced. A manifest can remain valid after a
lost completion acknowledgment; inspect it against the independent receipt.

## Restore and rollback boundary

Restore requires the independently approved exact manifest digest and exact
latest authoritative policy revision. A different digest or revision fails;
never select the newest directory by timestamp. This is an operator-supplied
high-water anchor, not a distributed rollback oracle. An old receipt cannot
establish that no later revocation or audit record existed. Before restoring,
the operator must reconcile the receipt with current authoritative policy and
all later evidence. If either is unavailable, keep business execution disabled.
No automatic fallback to an older archive or policy bootstrap is allowed.

Restore creates a new directory and never overwrites the source or a previous
restore. An attributable prepared intent beside the destination is written before
creating it; preserve this sidecar as recovery evidence. A pending marker blocks normal startup until all files pass manifest
verification, journal semantic/link/revision validation and a forced attributable
RESTORE_COMPLETED audit. The completion marker is forced last. Failed restores
remain quarantined for inspection; use a new destination for another attempt.
Do not open an incomplete destination with an older binary that lacks this gate.
Even a completed restore requires nonempty base policy/audit files before opening
the journals: missing history is never replaced with bootstrap policy.

The restored gateway does not start automatically. Each cold startup requires
`admission.recovery-authorization` pointing to a private OS-protected JSON file
outside the restore and archive. Its `manifestSha256` must match the restored
receipt, `minimumPolicyRevision` must reflect current independent authority and
must not exceed the restored policy, and `verifiedAt`/`expiresAt` must enclose
startup time with at most 30 seconds between them. `actor` and `reason` are
required operator/ticket references. Missing, future, expired or conflicting
authorization blocks startup, even for a fully valid older backup. Startup forces
RESTORE_AUTHORITY_VERIFIED before the service becomes available. The maintenance
restore itself requires no permission from the policy being restored.

The operator must obtain current authority and validate current
revocations/configuration/credentials before issuing that artifact; this is a
trusted deployment attestation, not a cryptographic freshness oracle. Do not
copy the attestation from a backup or issue one while current state is unknown.
It authorizes this cold start, not a 30-second running policy lease. Subsequent
admission still uses current journals, explicit expiries and normal monotonic
fallback. Lost
post-checkpoint execution evidence must be preserved/reconciled separately;
restore cannot reconstruct external effects. Existing service startup converts
unfinished admissions to UNKNOWN_AFTER_RESTART and never replays business work.
An uncertain outcome is never converted to failure, rollback or success merely
because a checkpoint or restart succeeded. New calls require fresh admission.

## Capacity and failure handling

Private storage status reports per-journal bytes/headroom, segment counts,
filesystem usable bytes and NORMAL/WARNING/CRITICAL states: warning at 80% of a
retained byte/segment budget, critical at 95%, or usable space below one configured
segment. Operators poll status and route warning/critical observations to their
monitoring/incident system; no external notification integration is supplied.
`capacityStatus` is a private application API; the CLI acquires exclusive locks
and is offline inspection, not an online readiness endpoint. It reports unresolved
work and latest lifecycle evidence. A last CHECKPOINT_STARTED without a terminal
event means INTERRUPTED_OR_UNACKNOWLEDGED: inspect the archive/receipt before
choosing a new operation. Incomplete restore intents/markers remain inspectable;
a partial intent or absence of completion is not permission to bootstrap.
Rate-based projection accepts an observed nonnegative byte rate and reports
seconds to journal exhaustion. Zero rate means unknown, not infinite retention.
The 90-day required capacity is rate * 7,776,000 seconds, separately for each
journal; this is a projection, not measured future throughput or a quota increase.
Measure rates from two retained-byte observations at least 15 minutes apart on
the same uninterrupted store, divide positive deltas by elapsed monotonic seconds
and round up. For missing/reset samples or no growth supply zero; runway is -1
(unknown). Rates assume future traffic/record sizes resemble that window, omit
future lifecycle overhead and do not establish 90-day capacity. No failure-audit
reserve is claimed. Filesystem space is shared and may change after inspection.

Unavailable archives and failed/short/full writes fail the operation without
changing policy, replaying work or removing evidence. Verify before acknowledging
success. Full source audit storage prevents new admission even if policy fallback
is fresh; maintenance may itself fail because its lifecycle audit cannot persist.
Alert and expand approved storage/repair availability; do not delete evidence to
recover. Restoring an archive does not free source capacity. Restart validates
retained journals; partial/corrupt history fails closed, with no automatic repair
that truncates evidence. An empty last rollover segment retains existing recovery.

## Required evidence and remaining limits

Exercise interrupted checkpoint writes, unavailable destinations, simulated
ENOSPC, corrupt/missing/uncommitted archives, independent receipt/revision mismatch,
writer exclusion, capacity thresholds/projection, interrupted restore startup,
segmented restore/restart and unresolved outcomes without business replay.
Run the full pinned Wrapper verification and retain exact results in WORKLOG.
No physical deletion, complete release gate, production RPO/RTO, disk-controller
power-loss, adversarial OS, clustered fencing or distributed rollback claim follows
from these local tests. Physical expiry needs a separate reviewed contract and
explicit deletion permission.

## Operator commands

After full verification, build the runtime classpath once (no gateway startup):

```powershell
.\mvnw.cmd -B -ntp dependency:build-classpath '-Dmdep.outputFile=target/s2-classpath.txt'
$storageClasspath = 'target/s2-reviewed/classes;' + (Get-Content target/s2-classpath.txt -Raw).Trim()
# Use private approved paths, a new archive directory and operator/ticket references.
java -cp $storageClasspath dev.mcp.gateway.admission.AuditCheckpoint checkpoint C:/private/journals E:/private/archive-001 operator-1 CHG-001 16777216 268435456 C:/private/bootstrap.json
java -cp $storageClasspath dev.mcp.gateway.admission.AuditCheckpoint verify E:/private/archive-001 <trusted-digest> <revision>
java -cp $storageClasspath dev.mcp.gateway.admission.AuditCheckpoint status C:/private/journals C:/private/bootstrap.json 16777216 268435456 0 0
java -cp $storageClasspath dev.mcp.gateway.admission.AuditCheckpoint restore E:/private/archive-001 C:/private/restored-001 <trusted-digest> <revision> operator-2 CHG-002 C:/private/bootstrap.json
```

Replace angle-bracket placeholders before running. The bootstrap path is required
by existing settings but maintenance rejects absent/empty source journals and
never provisions over them. Checkpoint uses the deployment's exact byte limits;
restore takes limits from the verified manifest. When checkpointing a restored
store, append its fresh recovery authorization path as the final optional argument.
`status` can inspect complete retained journals without authorizing business startup;
`executionReadiness` is explicitly NOT_EVALUATED_BY_STORAGE_INSPECTION. All commands
fail with a nonzero JVM exit on error; preserve stderr privately. Capacity failure
may prevent failure-audit persistence; keep an independent incident record.

Example independently provisioned startup authorization (generate fresh UTC times
only after current-authority reconciliation, protect the file with OS ACLs):

```json
{"manifestSha256":"<trusted-digest>","minimumPolicyRevision":2,"verifiedAt":"<now>","expiresAt":"<now-plus-30-seconds>","actor":"operator-2","reason":"CHG-002"}
```

Start the normal secured UC-01 gateway with the restored journal directory and
`--admission.recovery-authorization=C:/private/recovery-authorization.json`, retaining
its existing private credential/identity configuration. Do not use these commands
against a live coordinator or deployment without that operation's authorization.
