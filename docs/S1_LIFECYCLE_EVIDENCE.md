# S1 UC-01 lifecycle and queued-revocation evidence

Date: 2026-10-28. Scope: local single-writer delegated ordinary reads only.
Runtime pins and modern migration deferral remain as recorded in ADR 0014.
[ADR 0015](decisions/0015-uc01-lifecycle-and-control-capacity.md) defines the
administrative and timing contract. U5 and the full enterprise release remain
Partial; all later-mode and operational obligations retain their existing gates.

## Requirement-to-test evidence

Tests run a real random-port Boot/WebFlux gateway, official pinned MCP SDK clients,
an independent ephemeral JWT signer/JWKS server and independent REST mock. They
never contact the live coordinator. Direct cache tests supplement this protocol
evidence with deterministic exact time boundaries and journal reopen.

| Obligation | Named evidence | Observable result |
| --- | --- | --- |
| ID-06/08/10, OP-03/05 | tenantLifecycleIsAuthorizedVersionedAndRevokesExistingSession | Publisher denied; authorized tenant suspension denies an existing green session with zero calls; reactivation permits exactly one read; foreign tenant change denied. |
| ID-06/08/10 | organizationRecoveryAndTenantEnrollmentKeepRetiredIdsImmutable | Same-org suspension/recovery; cross-org denial; new tenants start pending; retirement prevents reuse for tenant and organization. |
| ID-06/10, AU-03 | selectiveMembershipsPreserveOtherTenantsAndDoNotCreateGrants | Selective human/application blue membership changes preserve green memberships and execution; grant sets unchanged; blue switch cannot borrow green delegation/resource/grants. |
| ID-08/10/11 | wholePrincipalLifecycleRequiresAuthorityForEveryExistingMembership | Blue-only admin cannot change a multi-tenant human/application's global status; explicitly provisioned authority in both memberships permits suspension; a current owner cannot bypass self-activation by simultaneously transferring ownership. |
| ID-01, IA-AC-01 | displayAndEmailChangesPreserveAuthoritySubjectAndCannotTransferGrants | Display rename and changed token name/email preserve grants and two successful reads; authority replacement denied; same email/subject with foreign issuer rejected with zero additional calls. Single configured trust authority; no positive multi-issuer linking is claimed. |
| OP-03, EX-04, IA-AC-23 | timingDefaultsRequireExplicitAuthorityPersistAndOnlyConstrainNewDelegations | Publisher and local admin cannot alter global defaults; explicitly provisioned authority in all policy tenants can tighten them; stale revision/above-ceiling changes rejected; existing grants/delegations unchanged; new excessive delegation denied and bounded issuance succeeds. |
| AU-08/09, EX-01, IA-AC-23/25 | VerifiedPolicyCacheTest.versionedTimingTightensFallbackAndSurvivesPolicyCopiesAndSerialization | 10-second versioned fallback succeeds one nanosecond before expiry and fails exactly at expiry; copies, JSON and journal reopen preserve timing; legacy snapshots use defaults. Existing retainedPolicyExpiresExactlyAtBoundAndFailuresNeverRenewIt still proves the 30-second ceiling. |
| OP-01/02/07, AU-09/10, IA-AC-21/22 | saturatedProtocolQueueRechecksCommittedRevocation (13 cases) | Two actual subscribed SDK calls wait behind controlled admission work; all 256 waiting slots filled; overflow denied. Administrative HTTP revocation completes before blocker release. Both calls then check revision 2, deny with no protected structured output and zero downstream calls. |
| ID-09, AU-02/09/10 | subscribedProtocolWorkExpiringInQueueNeverExecutes (delegation/token cases) | Valid at request arrival, expired before dequeue; fresh admission denies, zero downstream calls. |
| AU-09/10, OP-04/07/08 | protocolQueueTimeoutNeverDispatchesAfterWorkerRecovers | Five-second queue residence expires while worker is still blocked. Caller gets verification unavailable/not_attempted; reserved control capacity records unverified queue-failure evidence; worker recovery never dispatches the expired call. |

## Composed revocation matrix

Each case starts from its own Boot context and revision 1. It holds the actual
business queue worker outside the journal monitor, subscribes two SDK calls and
waits until both are queued. It fills the remaining 254 slots and proves another
submission is rejected. The 13 changes cover human/application suspension,
organization/tenant suspension, selected human/application membership removal,
delegation and human-grant revocation, server/publication suspension, catalog
membership removal, resource ownership withdrawal and credential withdrawal.

The control request must return success while the blocker and SDK futures remain
incomplete. Its HTTP response records changeId, revision, acceptedAt and effectiveAt.
A monotonic HTTP round-trip measurement must be below 60 seconds. After release,
assert both decisions follow ADMIN_EFFECTIVE in durable audit append order and
carry revision 2, distinct call IDs, queuedAt before effectiveAt and NOT_ATTEMPTED.
Credential/resource withdrawal produces verification unavailable; the other
cases produce access denied. Every denied result has no structured content;
every case's independent backend count stays zero.

Reproducible per-case artifacts are written to `target/s1-queued-<ACTION>.json`:
control timing/ID/revision, queue depth at commit, ordered call IDs/decisions and
downstream counters. These ignored artifacts contain synthetic identities and
no tokens/credentials. The permanent measured summary below is from full verify.
Already-admitted behavior remains the existing admission/outcome contract: it may
finish and retain attribution; no rollback is claimed.

## Verification

Final command: `.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/s1-reviewed' verify`.
Exit 0, BUILD SUCCESS in 3:34, completed 2026-10-28 at 19:01:32 +05:30.
Fresh report XML totals: **144 unit/startup and 60 integration tests**, zero
failures/errors/skips. Log: `target/s1-reviewed.log`; reports and executable JAR:
`target/s1-reviewed`. This covers the final owner-transfer correction.

The 13 final control round trips measured **17-52 ms**, with 26 denied queued calls
and zero downstream calls. Each case proves all 256 business slots occupied at
commit, revision 2 and durable ADMIN_EFFECTIVE before admission decisions. The
[retained machine-readable evidence](evidence/s1-queued-revocation.json) preserves
accepted/effective times, change/call IDs, revisions, ordered decisions and counters.
These are local functional measurements, not production latency objectives.

Focused red/green evidence:

- Tenant lifecycle regression failed before implementation (expected 200, actual
  403), then passed after implementation: `target/s1-red.log`, `target/s1-green.log`.
- `./mvnw.cmd -B -ntp -Dgateway.build-directory=target/s1-focused -Dtest=AdmissionWorkQueueTest,VerifiedPolicyCacheTest,Uc01AdmissionIT test`
  passed 46 tests, zero failures/errors/skips, in 2:14 before the final timeout
  audit and whole-principal checks were added. Log: `target/s1-focused.log`.
- Initial composed checks exposed an Integer/Long assertion mismatch in parsed
  audit revisions; the assertion now compares numeric values. A new timing-test
  helper name was corrected after a test compilation failure. Neither was a
  runtime admission bypass. Final verification covers the corrected source.

## Limits and following work

The five-second business queue budget prevents a call from retaining a queue
position for 30 seconds; it must fail unavailable and a later attempt gets a new
admission. Deterministic monotonic tests establish exact policy-fallback expiry;
these fixtures do not wait 30 seconds or simulate a multi-replica partition.
Control capacity is reserved but finite. A journal operation already holding the
monitor, an OS-stalled disk write, or a saturated control queue can delay/fail
administration. No hard OS I/O deadline, production capacity or cluster guarantee
is established. No successful completion is returned for uncompleted changes.

New organization provisioning remains private; no public platform enrollment or
cross-organization lifecycle authority is added. Keep an active console tenant
for recovery. Physical archive/checkpoint/restore/expiry belongs to S2's own
operational contract; actual deletion still requires explicit permission. Workloads,
approvals, sensitive reads/mutations and third-party execution remain disabled.
Modern S3/S7 stay gated by ADR 0014. S1 closure does not close U5 or the 32-row
identity/full catalog release gate.

The owner-transfer regression in wholePrincipalLifecycleRequiresAuthorityForEveryExistingMembership
failed before correction (expected 403, actual 200; target/s1-owner-review-red.log).
Checking the existing owner as well as the proposed owner closes this lifecycle
bypass. The final reviewed Wrapper run above passes for that correction.
