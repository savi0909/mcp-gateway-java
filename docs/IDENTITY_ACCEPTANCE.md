# Enterprise identity/authorization acceptance evidence

Status: all criteria **Pending**. Date: 2026-10-08. No v1.4 execution evidence yet.

[Requirements v1.4](requirements/Enterprise_MCP_Identity_Authorization_Requirements_v1.4.md)
are authoritative. Source AC-01 through AC-32 appear here as **IA-AC-01 through
IA-AC-32**, solely to distinguish them from the completed foundation's AC rows.
The original requirement coverage and expected results below are preserved from
v1.4 section 8. Planned increment labels describe work allocation, not evidence
or a reduction in required behavior.

Follow [the UC-01 plan](UC01_ADMISSION_PLAN.md) and
[ADR 0008](decisions/0008-uc01-admission-first.md). The full first-release gate is
all baseline MUST requirements and all 32 acceptance criteria, including UC-03,
UC-02 and approved-third-party behavior. UC-01 is the next bounded increment.
All disabled-mode denials applicable to its exposed surface still require tests.

Use Pending until executed evidence exists; Partial when only part of a source
row passes; Verified only when every part has observable evidence. For each
update record exact commands, test names, configuration, actual counts, denied
execution/output counters, boundary timing and evidence limits in WORKLOG.
Historical foundation tests do not verify these rows. Source IDs ID/AU/ST/OP/EX
retain their original meanings.

## Baseline matrix

| Tracker ID | Source requirement coverage | Planned increment | State | Expected observable result / next proof |
| --- | --- | --- | --- | --- |
| IA-AC-01 | ID-01 | UC-01 | Pending | Rename a user/change email: identity and grants persist. Same email from another authority receives no inherited grants. |
| IA-AC-02 | ID-02–04, ID-09 | UC-01 | Pending | Recognized user with unknown application, or recognized app with invalid user evidence: invocation does not execute. |
| IA-AC-03 | ID-05, ID-07, AU-02 | UC-01; extend in UC-03 | Pending | Inject another user's ID/delegation claims into prompt or tool arguments: no impersonation and no execution under that identity. An agent-run identifier alone supplies no authority. |
| IA-AC-04 | ID-08–11 | UC-01; extend to workloads | Pending | Pending/suspended/retired principal cannot invoke; owner cannot self-activate or self-grant; retired ID cannot be reused. Untrusted administrator enrollment is rejected; trusted initial provisioning is audited. |
| IA-AC-05 | ID-06–07 | UC-01 | Pending | Unauthorized tenant switch or mismatched organization/tenant: deny. Authorized switch grants only the new tenant's explicit scope. |
| IA-AC-06 | AU-01–03 | UC-01 | Pending | UC-01 with all conditions valid: one authorized read attributed to the application and Priya. Expired/missing delegation: no execution. |
| IA-AC-07 | AU-03, AU-05, AU-10 | UC-01 | Pending | App allows read/user denies, and reverse: deny both. Authorized tool against unauthorized payment: deny with no requested-tool execution or protected output. |
| IA-AC-08 | AU-06–07 | UC-01 | Pending | Registered/unpublished tool, missing catalog membership, unapproved contract, or hidden tool called directly: deny. |
| IA-AC-09 | AU-01, AU-09 | UC-01 | Pending | First session call succeeds; permissions change before next call: next call reflects current permissions. |
| IA-AC-10 | AU-04, UC-03 | UC-03 | Pending | Authorized autonomous read succeeds with app/workload attribution and no fabricated human. Adding a user ID does not enlarge scope. |
| IA-AC-11 | UC-03, ST-05 | UC-01 disabled-path checks; UC-03/provider completion | Pending | Autonomous mutation/sensitive read and third-party mutation/sensitive disclosure: deny under baseline defaults. |
| IA-AC-12 | ST-01–02, EX-03 | UC-01 classification/disabled paths; UC-02 completion | Pending | Unclassified operation or sensitive tool without admission approval: no execution, regardless of action approval. Newly enabled tools/contracts or operation categories receive no inherited grants or sensitive admission approval. |
| IA-AC-13 | UC-02, ST-02 | UC-02 | Pending | Eligible refund without action approval: approval-required; no mutation. Unauthorized refund: denied; approval cannot rescue it. |
| IA-AC-14 | UC-02 | UC-02 | Pending | Requester self-approval, wrong-tenant approver, or unauthorized approver: reject. Valid independent approval permits the exact authorized action. |
| IA-AC-15 | UC-02, ST-03 | UC-02 | Pending | Change payment, amount, currency, app/user, tenant, contract, or policy-relevant argument after approval: approval invalid; no execution. |
| IA-AC-16 | ST-02–03 | UC-02 | Pending | Expired/rejected/revoked/consumed approval and two concurrent uses: no invalid execution; at most one admission from the valid approval. |
| IA-AC-17 | UC-02, AU-09 | UC-02 | Pending | Requester/approver loses permission or payment becomes ineligible while awaiting approval: no mutation at admission. |
| IA-AC-18 | ST-04–06, ST-11 | UC-01 internal boundary; provider completion | Pending | Internal or external server proposes identity/tenant/permission changes or an unapproved destination: no enlargement of authority or disclosure. |
| IA-AC-19 | ST-07–10 | UC-01; extend to workloads/providers | Pending | Tenant/provider credential crossover attempt or missing credential: deny; no broad fallback. No inbound MCP token appears in downstream request. |
| IA-AC-20 | ID-09, ST-08 | UC-01 | Pending | Expired evidence, untrusted authority, or access evidence intended for another recipient: reject. Sign-in assertion alone cannot invoke a tool. |
| IA-AC-21 | OP-01–02 | UC-01 supported contexts; extend to workloads/approvals/providers | Pending | Revoke user, app, workload, delegation, grant, approval, tenant membership, or server eligibility: completion within 60 seconds; subsequent admissions denied even in existing sessions. |
| IA-AC-22 | OP-02, AU-09 | UC-01; extend to sensitive actions | Pending | Revocation occurs while work is queued: denied at admission. Already admitted work retains accurate attribution and does not claim rollback. |
| IA-AC-23 | AU-09, OP-03, EX-01, EX-04 | UC-01; extend to later modes | Pending | Delay an allowed action beyond 30 seconds, change policy, then resume: fresh decision applied; stale permission not reused. An authorized timing/default change is enforced and versioned; existing grants retain their defined meaning and earlier evidence stays interpretable. |
| IA-AC-24 | OP-04–06 | UC-01; extend to approvals/providers | Pending | Inspect allow, deny, approval, and administration records: required evidence exists; no secret/raw unnecessary payload; unauthorized/cross-tenant audit access denied. |
| IA-AC-25 | OP-07–08 | UC-01; extend to approvals/providers | Pending | Admission audit persistence, identity verification, or policy verification unavailable: no operation executes; recovery requires fresh checks. |
| IA-AC-26 | OP-09, ST-07, EX-05 | UC-01; repeat with later modes | Pending | Interleave at least 100 calls across 50 users, two apps, and two tenants: each call has correct identity, authority, credentials, and audit scope; no context leakage. Repeat with newly provisioned names/resources outside the sample use cases. This is a functional isolation test, not a production capacity claim. |
| IA-AC-27 | UC-02, OP-04, OP-07 | UC-02 | Pending | Provider times out after mutation admission: report uncertain outcome, preserve evidence, and do not automatically reuse approval to repeat the effect. |
| IA-AC-28 | ID-10, AU-07, OP-03 | UC-01 | Pending | Catalog publisher lacks security grants: cannot change identity/permission policy or execute a tool solely because it published it. |
| IA-AC-29 | AU-02, OP-02 | UC-01 rejection/renewal; UC-03 completion | Pending | Attempt onward delegation or delegated-to-autonomous fallback: deny. Renewal after user suspension/revocation does not restore authority. |
| IA-AC-30 | ST-10–11 | UC-01 internal boundary; provider completion | Pending | Malicious provider requests full identity context, secrets, or unauthorized data: withhold them and record the failed disclosure attempt. |
| IA-AC-31 | OP-05–06 | UC-01; extend to later administration | Pending | Audit viewer cannot modify/delete records; retention expiry is controlled and audited; administration changes retain before/after evidence. |
| IA-AC-32 | OP-08, AU-08, EX-02 | UC-01; extend to all supported contexts | Pending | Missing required resource ownership or policy attributes: verification-failure outcome; no protected output or execution. Unsupported principal types or policy conditions remain denied rather than ignored. |

## Evidence and dependencies

- No source acceptance row is verified by this documentation update. Catalog
  requirements v1.3 are missing; their publication/membership/pinned-contract
  semantics must be reviewed before their final implementation contract.
- Controlled fixtures need two organizations, two tenants in one organization,
  at least two applications, differently entitled humans, a workload, an internal
  server and an approved third-party server across the full baseline. UC-01
  initially enables only delegated ordinary internal reads.
- Prove every denied operation has zero requested-tool execution and zero
  protected output. Count policy/evidence lookups separately. Test at/across
  timing boundaries: delegation <=15 minutes, action approval <=10 minutes,
  revocation completion <=60 seconds, decision reuse <=30 seconds, audit 90 days.
- Persist invocation-admission evidence before execution. Test outages and fresh
  authorization after recovery; record admitted work and unresolved outcomes.
  Audit inspection/export and retention expiry require scoped, attributable control.
- Map individual functional requirements to named tests as implementation proceeds;
  this planned matrix does not claim complete requirement-to-test traceability.
- The original [foundation evidence](ACCEPTANCE.md) remains intact and separate.
  Run full Wrapper verification after implementation, using target/foundation
  when preserving an existing demo JAR. Mock evidence is not live IAM/provider
  integration or production capacity evidence.
