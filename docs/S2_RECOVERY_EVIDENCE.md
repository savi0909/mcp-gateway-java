# S2 local checkpoint and recovery evidence

Date: 2026-10-28. Scope: the owner's non-destructive S2 increment, governed by
[the storage contract](S2_STORAGE_CONTRACT.md) and
[ADR 0016](decisions/0016-offline-audit-checkpoint-and-restore.md).
Contract review before S4 is outstanding; physical expiry remains unavailable.

## Final verification

Command: `.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/s2-reviewed' verify`.
Exit 0, BUILD SUCCESS, 04:03, finished 2026-10-28 19:44:22 +05:30.
Fresh XML totals: **152 unit/startup and 62 integration tests**, zero failures,
errors or skips. Executable JAR/reports: `target/s2-reviewed`; log:
`target/s2-reviewed.log`; parsed counts: `target/s2-reviewed-counts.json`.
Existing foundation/S1 Boot/SDK regression tests ran on the pinned product stack.

No live coordinator operation, deployment, existing-demo restart, deletion,
dependency change, modern migration or workload execution occurred.

## New test evidence

`AuditCheckpointTest` has eight tests, using real newly created files and no cleanup
deletion. Its storage writer injects partial bytes followed by IOException:

| Test | Observable result |
| --- | --- |
| checkpointRestoresAllSegmentsExpiredEvidenceAndUnresolvedAdmissions | Every one of 35 padded records, segmented links, policy revision, a 2020 unresolved admission and attributed restore event survive restore and two reopens |
| interruptedOrFullCheckpointNeverPublishesAndSourceCanRestart | Partial write at each of two journal copies, manifest and completion publication denies restore; original source reopens with CHECKPOINT_FAILED evidence |
| unavailableArchiveAndActiveWriterFailWithoutReplacingEvidence | Active writer excludes maintenance; unavailable destination fails; occupied path remains unchanged and source policy survives |
| corruptArchivesWrongReceiptsAndOldRevisionsCannotRestore | Corrupt data, mismatched independently supplied digest and revision reject restore |
| interruptedRestoreCannotBecomeAProvisionedGateway | Partial write at each of external intent, internal pending marker, two journal copies and completion marker blocks normal startup and reuse of destination |
| capacityProjectionWarnsBeforeExhaustionWithoutIncreasingBudget | WARNING above 80%, explicit 200 bytes/s projects 1,555,200,000 bytes over 90 days, bounded runway; absent rate returns -1 |
| restoredBackupCannotStartWithoutFreshIndependentAuthorityOrUndoLaterRevocation | Missing/expired external authority and independently required revision 2 reject valid revision-1 restored data; fresh matching authority permits reopening |
| completedRestoreCannotBootstrapOverMissingBaseHistory | Moving both base files aside leaves preserved evidence and prevents startup before either base file is recreated or seeded |

`Uc01AdmissionIT` adds two running Boot/SDK cases with independent issuer/backend
mocks and random ports:

- `verifiedCheckpointRestorePreservesRevocationAndUnknownOutcomeWithoutReplay`:
  HTTP revocation commits revision 2; a synthetic lost-outcome fixture is copied
  through checkpoint/restore. Fresh external authorization permits startup, which
  records UNKNOWN_AFTER_RESTART. Recovery and a revoked SDK call make zero backend
  requests. A fresh authorized SDK call makes exactly one. A second restart neither
  replays it nor duplicates the unknown outcome. The unfinished fixture is synthetic;
  earlier `AdmissionBoundaryTest` supplies actual lost outcome-write evidence.
- `exhaustedAuditStorageDeniesActualSdkDispatchAndSurvivesRestart`: fills the
  131,072-byte fixture audit budget, observes CRITICAL, denies an actual SDK read
  with VERIFICATION_UNAVAILABLE and absent protected output, zero backend requests.
  Restart preserves every accepted filler record and makes no requests.

## Operator command smoke and iterations

`.\mvnw.cmd -B -ntp dependency:build-classpath '-Dmdep.outputFile=target/s2-classpath.txt'`
passed. Using that classpath, separate JVM invocations of `AuditCheckpoint
checkpoint`, `verify`, `restore`, and `status` all exited 0 using
`target/s2-final/classes` against a synthetic retained fixture
(receipt/paths: `target/s2-cli-smoke.json`). The reviewed build repeated all four
commands using `target/s2-reviewed/classes`, all exit 0; final receipt/paths/result:
`target/s2-reviewed-cli-smoke.json`.
Status reported unresolvedWork=1 and execution readiness explicitly not evaluated;
offline inspection did not start the gateway or authorize restored business work.

Initial test-first compilations under `target/s2-red` and `target/s2-authority-red`
failed on the intentionally missing checkpoint/capacity and recovery-authorization
APIs. Focused journal/boundary/checkpoint tests then passed. First two protocol
tests passed in `target/s2-protocol`; shutdown exposed open test-client streams,
so the restart fixture now closes its clients before restarting. The strengthened
authority gate plus focused protocol run passed in `target/s2-focused-2` (22
unit/boundary and 2 integration tests); the final full run above supersedes it.

Review subsequently reproduced an unsafe empty-store interpretation when both base
journals were absent from a completed restore. The new regression failed as expected
under `target/s2-review-red`; restored stores now require nonempty base history
before opening either journal, so bootstrap cannot replace missing recovery evidence.
The reviewed full verification and command smoke supersede the first full run.

## Evidence limits and next gate

The storage operator owns OS authorization, all-scope archive privacy, independent
receipts and truthful current-authority reconciliation. A fresh external attestation
is enforced; it does not discover missing later revocations/evidence or protect
against a privileged operator falsely attesting current state. Reconcile later
audit/outcomes separately before admitting traffic. Every cold start of restored
storage requires fresh external authorization.

Local forced/read-back writes and injected faults do not prove power-loss directory
durability, production backup replication, host ENOSPC/ACL behavior, distributed
rollback/fencing or RPO/RTO. Capacity rates are externally sampled; alert routing is
not integrated. Physical evidence is never purged and storage is not reclaimed.
Identity/catalog release gates and U5 remain partial. Review the storage contract
before separately selecting S4; preserve pinned runtime and defer modern migration.
