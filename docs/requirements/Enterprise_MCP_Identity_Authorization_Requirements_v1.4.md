# Enterprise MCP Platform — Identity & Authorization Requirements v1.4

Project: Enterprise MCP Platform, Option C  
Date: 8 October 2026  
Status: Baseline requirements selected using recommended defaults; ready for implementation planning  
Audience: Product owner, coding agent, developers, and security reviewers

## 1. Purpose and boundaries

Define observable identity, authorization, and trust-boundary behavior for internal enterprise AI applications consuming internal and approved third-party MCP servers. Build a secure initial capability set that can be extended without weakening existing guarantees.

This document specifies use cases, functional requirements, defaults, and acceptance criteria. It does not choose architecture, deployment topology, vendors, frameworks, storage, or token formats. It supplements the completed catalog requirements v1.3; it does not replace them. An implementation has not been validated by producing this specification.

MUST requirements and all baseline acceptance criteria are required for the first release. Future enhancements in section 10 are not part of that release. The defaults below resolve initial policy choices so implementation need not wait for another policy-design session.

## 2. Recommended initial defaults

These are project policy choices, not universal MCP defaults.

| Area | First-release default |
| --- | --- |
| Access | Deny unless every applicable condition explicitly permits the invocation. |
| Consumers | Approved internal applications only; no anonymous or self-approved consumers. |
| Identity authorities | Accept only explicitly approved identity authorities for the relevant organization. |
| Execution modes | Explicitly human-delegated or autonomous-workload; no automatic fallback between them. |
| Human-delegated access | Application permission, human permission, delegation, and resource policy must all allow the action. |
| Agent identity | Use application/workload identity as the authorized actor; record each agent run separately. Give an independently authorized agent its own workload identity. |
| Organization and tenant | Every invocation belongs to one organization and one tenant. Default to one tenant per organization; preserve the distinction for future multi-tenant organizations. |
| Permissions | Explicit grants for principal, application where applicable, tenant, tool, operation, and resource scope. No implicit wildcard or parent-organization grants. |
| Reads | Authorized ordinary reads require no per-request human approval. Sensitive-data reads are classified separately. |
| Sensitive actions | All mutations and sensitive-data reads require tool-level admission approval and independent per-action human approval. |
| Autonomous workloads | Read-only initially; sensitive reads and mutations are disabled. |
| Third-party servers | Explicitly approved, read-only, non-sensitive tools initially; approved data disclosure and isolated provider credentials required. |
| Delegation | Initial platform delegation grants expire within 15 minutes; renewal must re-establish current eligibility. |
| Action approval | Single action, single use, expires after 10 minutes; no self-approval. |
| Revocation | Complete within 60 seconds of accepting the administrative request; deny new invocations after completion. |
| Delayed execution | Recheck authorization at execution admission; a permission decision older than 30 seconds cannot be reused. |
| Audit | Record every invocation decision and every security administration change; retain for 90 days initially. |
| Failure | Missing or unverifiable required identity, authority, policy, approval, or audit evidence prevents execution. |

## 3. Identity and organization requirements

| ID | Functional requirement |
| --- | --- |
| ID-01 | A human MUST have a stable identifier within an approved identity authority. Display name and email changes MUST NOT create or transfer authority. Equal email addresses from different authorities MUST NOT be treated as the same identity without approved linking. |
| ID-02 | Every application MUST have a distinct approved identity, accountable owner, organization, lifecycle status, and permitted tenant memberships. Application approval MUST NOT imply tool permission. |
| ID-03 | Every autonomous workload MUST have its own approved identity, owner, organization, tenant scope, associated application, and lifecycle status. Workloads MUST NOT impersonate humans. |
| ID-04 | Human-delegated invocations MUST identify the application and represented human separately. Autonomous invocations MUST identify the application and workload and have no represented human. |
| ID-05 | Agent-run and correlation identifiers MUST support attribution but MUST NOT grant permissions. Independently authorized agents MUST meet all workload identity requirements. |
| ID-06 | Organizations and tenants MUST have stable identifiers and lifecycle status. Users MAY have multiple explicit memberships; each request MUST select exactly one authorized tenant in the stated organization. |
| ID-07 | Identity, tenant membership, delegation, and execution mode MUST come from verifiable authorized context. Prompts, model output, tool arguments, display names, and MCP session identifiers MUST NOT establish these facts by themselves. |
| ID-08 | The lifecycle MUST support pending, active, suspended, and retired principals. Only active principals and active organization/tenant memberships may authorize invocations. Retired identifiers MUST NOT be reassigned to another principal. |
| ID-09 | Invalid, expired, untrusted, or incorrectly targeted identity/access evidence MUST be rejected. A human sign-in assertion alone MUST NOT authorize MCP tool access. |
| ID-10 | Identity status and security grants MUST be manageable only by principals with explicit administrative permission for that organization/tenant. Publisher, application owner, and security administrator privileges MUST be separate grants. |
| ID-11 | An application owner MUST NOT approve its own activation or grant itself additional security privileges. Initial administrator enrollment MUST require explicit trusted provisioning and an audit record; no public administrator self-enrollment. |

Logical invocation facts, independent of their eventual representation: organization; tenant; application; execution mode; represented human and delegation reference for delegated calls OR workload for autonomous calls; agent run; correlation reference; server; tool and contract version; operation; target resources; arguments needed for policy; decision and policy version.

## 4. Delegation and authorization requirements

| ID | Functional requirement |
| --- | --- |
| AU-01 | Each invocation MUST be independently authorized, including follow-up calls in an existing MCP session. A successful sign-in, previous invocation, or catalog response MUST NOT authorize later calls. |
| AU-02 | Delegation MUST bind one represented human to one approved application, organization, tenant, allowed operation/resource scope, expiry, and revocation state. Requests outside that binding MUST be denied. Initial onward delegation to another application or workload is disabled. |
| AU-03 | Human-delegated authority MUST be the intersection of application permission, human permission, delegation scope, tenant/resource restrictions, and current action policy. Application privilege MUST NOT compensate for missing human privilege or vice versa. |
| AU-04 | Autonomous authority MUST require both approved application entitlement and workload permission within the requested tenant/resource scope. It MUST NOT borrow an owner or employee's human permissions. |
| AU-05 | Permission MUST distinguish read and mutation operations, individual tools, and target resources. Tool arguments affecting authorization, including tenant, resource, amount, recipient, and data category, MUST be evaluated before execution. |
| AU-06 | Access MUST require an active published capability, explicit application catalog membership, an approved pinned contract, required sensitive-tool admission approval, and relevant provider restrictions, in addition to identity and invocation authorization. |
| AU-07 | Catalog filtering MUST honor the validated application and represented human/workload context. Hidden tools MUST remain inaccessible through direct calls or guessed identifiers. Visibility and execution permission MUST remain distinct. |
| AU-08 | Required facts that cannot be established reliably MUST cause a verification-failure outcome, not a permissive default. Unknown policy conditions and unsupported authorization-relevant attributes MUST NOT be ignored. |
| AU-09 | A queued, retried, or resumed invocation MUST recheck current authorization at execution admission. Changes to identity, grants, delegation, catalog status, contract, resource ownership, or policy MUST affect the new decision. |
| AU-10 | An authorization denial MUST prevent the requested tool operation and return no protected tool output. Evidence lookups needed to evaluate policy do not constitute executing the requested operation. |

Authorization outcomes MUST distinguish: permitted; authentication required/invalid; denied; verification unavailable; and action approval required. These are behavioral outcomes, not prescribed API status codes. Caller-visible explanations MUST avoid revealing protected identities, resources, or policy internals. Authorized security reviewers MUST receive a useful audited reason.

## 5. Three baseline use cases

### UC-01 — Human-delegated ordinary read

Priya, a support employee in Tenant A, asks the approved Support Assistant to retrieve payment P123's non-sensitive status. The payment belongs to Tenant A and is within her permitted support-resource scope.

1. Establish the application, Priya, Tenant A, and valid delegation independently of the prompt.
2. Verify catalog/contract eligibility and both application and human permissions.
3. Authorize the specific payment and permitted response data.
4. Execute the lookup and record the decision and execution result with both identities.

If any condition fails, the payment-status operation does not execute. If the tool would disclose sensitive fields, the sensitive-read requirements apply instead of treating it as an ordinary read.

### UC-02 — Human-delegated sensitive mutation

Priya asks the Support Assistant to refund INR 2,000 for payment P123. The refund tool already has separate sensitive-tool admission approval. Priya and the application have refund-request permission for this payment and amount.

1. Establish all identities and evaluate the exact refund action, including current payment eligibility and amount.
2. Create a pending action approval only after the requester is eligible. Show the approver the application, requester, tenant, payment, amount, currency, and intended consequence.
3. A different human with explicit refund-approval permission for this tenant/resource scope approves or rejects it. The approver MUST be active, verified, and permitted to approve the same action at approval time and execution admission.
4. Before execution, recheck requester authority, approver authority, approval validity, unchanged action details, tool eligibility, and current business policy.
5. Admit the approved action at most once and record approval, execution, and outcome evidence. If the external outcome is uncertain, report uncertainty; do not automatically repeat the mutation using the same approval.

Approval is an additional condition, never a substitute for permission. Changing the amount, currency, payment, tool/contract, requester, application, organization, tenant, or other policy-relevant arguments invalidates it. Retries after an uncertain outcome require confirmation of the original result or a separately authorized new action.

### UC-03 — Autonomous workload ordinary read

An approved reconciliation workload reads permitted payment statuses for Tenant A without a signed-in human.

1. Establish the application and distinct workload identity in autonomous mode.
2. Verify the workload's explicit tenant/resource permissions and the application's catalog entitlement.
3. Permit only the ordinary read scope, record the workload and owning application, and disclose only allowed fields.

Supplying Priya's user ID does not create delegation. A refund attempt, sensitive read, or cross-tenant read is denied. Losing human delegation in UC-01 MUST NOT silently convert that invocation into UC-03.

## 6. Sensitive operations, trust, and credentials

| ID | Functional requirement |
| --- | --- |
| ST-01 | Every tool/operation MUST be classified as ordinary read, sensitive read, or mutation by an authorized security administrator. Missing classification disables invocation. New tools and changed contracts MUST NOT inherit sensitive-operation approval automatically. |
| ST-02 | Sensitive-tool admission approval and individual action approval MUST be separate, attributable records. Pending, rejected, expired, revoked, or consumed action approvals MUST NOT authorize execution. |
| ST-03 | An action approval MUST bind the full policy-relevant action described in UC-02 and be admitted at most once even under concurrent attempts. A stable action reference MUST link approval, admission, and resulting outcome. |
| ST-04 | Internal location MUST NOT exempt a server from access controls. Each server MUST have an approved identity, accountable owner, trust classification, permitted tools/contracts, and status. |
| ST-05 | Third-party server use MUST require explicit provider approval and an allowed data-disclosure scope. Initially only ordinary reads are enabled. An unapproved provider, sensitive disclosure, or external mutation MUST be denied. |
| ST-06 | Server instructions, tool descriptions, and tool results MUST be treated as untrusted content. They MUST NOT change identity, tenant, execution mode, grants, approval, disclosure scope, or trusted destinations. |
| ST-07 | Credentials MUST be isolated across organizations, tenants, applications, represented users/workloads, providers, and authorized purposes. A grant for one context MUST NOT select or expose another context's credentials. |
| ST-08 | Credentials MUST be restricted to the intended recipient and minimum required authority. Incoming MCP access credentials MUST NOT be passed unchanged to downstream services. Any exchanged or separately obtained downstream authority MUST respect the invocation's permitted scope and provider trust boundary. |
| ST-09 | Credential absence, expiry, revocation, or insufficient scope MUST prevent execution. No fallback to an administrator credential, another tenant's account, or a broader shared account is permitted. |
| ST-10 | Secrets MUST NOT appear in prompts, model-visible tool output, caller-visible errors, or audit payloads. Provider requests MUST disclose only approved identity attributes and data; full internal identity context is not sent by default. |
| ST-11 | Each actual server/provider destination and identity authority MUST be approved for the requested purpose. Caller arguments, server content, metadata discovery, or redirects MUST NOT introduce an unapproved destination or authority. |

## 7. Revocation, audit, and failure requirements

| ID | Functional requirement |
| --- | --- |
| OP-01 | Authorized administrators MUST be able to suspend principals/memberships, revoke grants/delegations/approvals, and disable servers, tools/contracts, or providers. Acceptance and effective completion MUST be distinguished. Effective completion MUST occur within the default 60-second bound. |
| OP-02 | Invocations admitted after effective revocation MUST be denied, including calls within existing sessions and queued work. Pending approvals MUST NOT preserve revoked authority. An already admitted operation may finish; revocation MUST NOT claim to undo external effects. Record affected in-flight actions and their outcomes when known. |
| OP-03 | Security policy changes MUST be attributable and versioned. Widening permissions, changing sensitive classifications, or modifying defaults requires explicit administrative authority and audit evidence. Configuration changes MUST NOT silently reinterpret existing grants. |
| OP-04 | Every invocation decision MUST record time, organization, tenant, verified principals, execution mode, agent/correlation reference, target server/tool/contract, minimally necessary resource/action facts, policy version, decision/reason, delegation/approval references where applicable, and execution outcome if executed. Invalid supplied identities MUST be marked unverified, never logged as authenticated principals. |
| OP-05 | Identity enrollment/linking, membership changes, grants, revocations, approvals, provider approvals, policy changes, and audit access MUST be audited with actor, subject, before/after scope, and time. |
| OP-06 | Audit inspection/export MUST require explicit organization/tenant-scoped permission. Ordinary callers MUST NOT modify or delete security records. Expiry under the approved retention policy MUST itself be controlled and auditable. |
| OP-07 | Execution MUST NOT proceed without durable invocation-admission audit evidence. Pending outcome recording MUST preserve the admitted action and be completed when evidence becomes available; uncertain outcome MUST remain visibly uncertain. |
| OP-08 | Identity, policy, provider-credential, or approval verification failure MUST prevent execution. Recovery MUST trigger fresh authorization rather than release previously denied work automatically. |
| OP-09 | Security context MUST remain isolated across simultaneous requests and session reuse. Connection/session continuity MUST NOT substitute for current identity or authority. |

## 8. Baseline acceptance matrix

Use controlled fixtures, including two organizations, two tenants in one organization, at least two applications, users with different grants, one autonomous workload, an internal server, and an approved third-party server. Verify denied operations have zero requested-tool executions and return zero protected output. All time-bound criteria MUST be tested at and across their configured expiry/effective boundaries.

| Test | Requirement coverage | Expected observable result |
| --- | --- | --- |
| AC-01 | ID-01 | Rename a user/change email: identity and grants persist. Same email from another authority receives no inherited grants. |
| AC-02 | ID-02–04, ID-09 | Recognized user with unknown application, or recognized app with invalid user evidence: invocation does not execute. |
| AC-03 | ID-05, ID-07, AU-02 | Inject another user's ID/delegation claims into prompt or tool arguments: no impersonation and no execution under that identity. An agent-run identifier alone supplies no authority. |
| AC-04 | ID-08–11 | Pending/suspended/retired principal cannot invoke; owner cannot self-activate or self-grant; retired ID cannot be reused. Untrusted administrator enrollment is rejected; trusted initial provisioning is audited. |
| AC-05 | ID-06–07 | Unauthorized tenant switch or mismatched organization/tenant: deny. Authorized switch grants only the new tenant's explicit scope. |
| AC-06 | AU-01–03 | UC-01 with all conditions valid: one authorized read attributed to the application and Priya. Expired/missing delegation: no execution. |
| AC-07 | AU-03, AU-05, AU-10 | App allows read/user denies, and reverse: deny both. Authorized tool against unauthorized payment: deny with no requested-tool execution or protected output. |
| AC-08 | AU-06–07 | Registered/unpublished tool, missing catalog membership, unapproved contract, or hidden tool called directly: deny. |
| AC-09 | AU-01, AU-09 | First session call succeeds; permissions change before next call: next call reflects current permissions. |
| AC-10 | AU-04, UC-03 | Authorized autonomous read succeeds with app/workload attribution and no fabricated human. Adding a user ID does not enlarge scope. |
| AC-11 | UC-03, ST-05 | Autonomous mutation/sensitive read and third-party mutation/sensitive disclosure: deny under baseline defaults. |
| AC-12 | ST-01–02, EX-03 | Unclassified operation or sensitive tool without admission approval: no execution, regardless of action approval. Newly enabled tools/contracts or operation categories receive no inherited grants or sensitive admission approval. |
| AC-13 | UC-02, ST-02 | Eligible refund without action approval: approval-required; no mutation. Unauthorized refund: denied; approval cannot rescue it. |
| AC-14 | UC-02 | Requester self-approval, wrong-tenant approver, or unauthorized approver: reject. Valid independent approval permits the exact authorized action. |
| AC-15 | UC-02, ST-03 | Change payment, amount, currency, app/user, tenant, contract, or policy-relevant argument after approval: approval invalid; no execution. |
| AC-16 | ST-02–03 | Expired/rejected/revoked/consumed approval and two concurrent uses: no invalid execution; at most one admission from the valid approval. |
| AC-17 | UC-02, AU-09 | Requester/approver loses permission or payment becomes ineligible while awaiting approval: no mutation at admission. |
| AC-18 | ST-04–06, ST-11 | Internal or external server proposes identity/tenant/permission changes or an unapproved destination: no enlargement of authority or disclosure. |
| AC-19 | ST-07–10 | Tenant/provider credential crossover attempt or missing credential: deny; no broad fallback. No inbound MCP token appears in downstream request. |
| AC-20 | ID-09, ST-08 | Expired evidence, untrusted authority, or access evidence intended for another recipient: reject. Sign-in assertion alone cannot invoke a tool. |
| AC-21 | OP-01–02 | Revoke user, app, workload, delegation, grant, approval, tenant membership, or server eligibility: completion within 60 seconds; subsequent admissions denied even in existing sessions. |
| AC-22 | OP-02, AU-09 | Revocation occurs while work is queued: denied at admission. Already admitted work retains accurate attribution and does not claim rollback. |
| AC-23 | AU-09, OP-03, EX-01, EX-04 | Delay an allowed action beyond 30 seconds, change policy, then resume: fresh decision applied; stale permission not reused. An authorized timing/default change is enforced and versioned; existing grants retain their defined meaning and earlier evidence stays interpretable. |
| AC-24 | OP-04–06 | Inspect allow, deny, approval, and administration records: required evidence exists; no secret/raw unnecessary payload; unauthorized/cross-tenant audit access denied. |
| AC-25 | OP-07–08 | Admission audit persistence, identity verification, or policy verification unavailable: no operation executes; recovery requires fresh checks. |
| AC-26 | OP-09, ST-07, EX-05 | Interleave at least 100 calls across 50 users, two apps, and two tenants: each call has correct identity, authority, credentials, and audit scope; no context leakage. Repeat with newly provisioned names/resources outside the sample use cases. This is a functional isolation test, not a production capacity claim. |
| AC-27 | UC-02, OP-04, OP-07 | Provider times out after mutation admission: report uncertain outcome, preserve evidence, and do not automatically reuse approval to repeat the effect. |
| AC-28 | ID-10, AU-07, OP-03 | Catalog publisher lacks security grants: cannot change identity/permission policy or execute a tool solely because it published it. |
| AC-29 | AU-02, OP-02 | Attempt onward delegation or delegated-to-autonomous fallback: deny. Renewal after user suspension/revocation does not restore authority. |
| AC-30 | ST-10–11 | Malicious provider requests full identity context, secrets, or unauthorized data: withhold them and record the failed disclosure attempt. |
| AC-31 | OP-05–06 | Audit viewer cannot modify/delete records; retention expiry is controlled and audited; administration changes retain before/after evidence. |
| AC-32 | OP-08, AU-08, EX-02 | Missing required resource ownership or policy attributes: verification-failure outcome; no protected output or execution. Unsupported principal types or policy conditions remain denied rather than ignored. |

## 9. Requirements for extensibility

- EX-01: Organization/tenant structure, approved authorities, memberships, tool classifications, resource scopes, provider trust, and timing defaults MUST be changeable through authorized policy administration rather than assumptions tied to the three sample use cases.
- EX-02: New principal types, delegated relationships, tool categories, and contextual policies MUST have explicit supported semantics and acceptance tests. Unknown types/conditions remain denied until supported.
- EX-03: New capabilities MUST NOT automatically enlarge existing grants. Enabling future third-party writes or autonomous mutations requires explicit policy enablement and new grants/approvals.
- EX-04: Policy and contract evolution MUST retain interpretable decision evidence. Existing pinned contracts remain governed by v1.3; invalidated security approval prevents use even if the contract remains pinned.
- EX-05: Built-in example users, tools, amounts, and tenant names are fixtures, not special production cases. The same rules MUST work for newly provisioned principals and resources.

## 10. Deferred enhancements and later policy decisions

The initial release defaults are sufficient to start implementation. These decisions become necessary only before enabling the corresponding enhancement; disabled features MUST NOT be silently approximated.

| Enhancement | Decision needed before enabling |
| --- | --- |
| Autonomous mutations | Permitted operations, monetary/action limits, approval rules, and accountable owner. |
| Third-party sensitive reads or writes | Allowed data, provider assurance, geographic restrictions if required, and action approvals. |
| Multi-hop/sub-agent delegation | Permitted delegation depth, actor-chain attribution, scope attenuation, and revocation propagation. |
| Group/role-derived permissions | Membership authority, inheritance/conflict rules, and change/revocation behavior. |
| Risk-based step-up authorization | Operations requiring stronger identity assurance, freshness, signals, and failure behavior. |
| Multiple approvers or emergency access | Quorum, separation of duties, expiry, scope, review, and audit rules. No emergency bypass initially. |
| Broader organizational sharing | Explicit cross-tenant consent/resource-sharing semantics. No inherited cross-tenant authority initially. |
| Production-scale objectives | Measured concurrency/throughput, decision-latency targets, availability, and tested revocation behavior under load. |
| Longer audit retention | Organization policy, search/export access, and data minimization requirements. |

## 11. Coding-agent handoff and completion evidence

Implement the baseline MUST requirements and AC-01 through AC-32. Use the existing project conventions after inspecting its repository instructions. Make implementation choices in a separate design record; this requirements document does not mandate a gateway, policy engine, database, framework, or deployment platform.

Suggested delivery slices:

1. UC-01, identity/tenant lifecycle, per-invocation permission checks, catalog eligibility integration, and audit.
2. UC-03 and interleaved identity/credential isolation.
3. UC-02 approval behavior, revocation, and remaining internal/third-party trust-boundary tests.

These are implementation increments, not permission to expose an incomplete baseline as production-ready. A slice may expose only the operations whose applicable requirements pass; unsupported mutations, external providers, or execution modes stay disabled.

Use controlled identity/provider fixtures for automated tests. A fixture-backed demonstration MUST be labeled as such and MUST NOT be reported as live identity-provider/provider integration. Real integration must reject unverified caller assertions; test identities must not become a production authentication fallback.

Return requirement-to-test traceability, test results, tested configuration, policy defaults, supported/disabled features, and any unresolved integration limitations. Mark a requirement complete only with observable evidence. Do not claim Uber/Netflix/Google production-scale readiness from baseline functional tests.

## 12. Standards alignment references

These references ground the identity/access boundaries. They are not a full protocol contract or a technology/vendor selection. The coding agent must explicitly identify its targeted MCP version and validate applicable protocol conformance in the later API/protocol-contract work.

- [MCP Authorization, 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization): resource-targeted access evidence, audience validation, restricted authority, and separate downstream credentials support ID-09 and ST-08. Human sign-in alone does not establish all platform permissions.
- [MCP Security Best Practices, 2025-11-25](https://modelcontextprotocol.io/docs/2025-11-25/tutorials/security/security_best_practices): token passthrough, session impersonation, and untrusted discovery destinations support ID-07, ST-06–11, and OP-09.
- [OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html): human authentication context is distinct from permission to execute tools. OAuth authorization and OIDC authentication integration must preserve that distinction; this specification does not select an identity provider or prescribe token encoding.

No catalog publication, server registration, agent run, session ID, or human approval creates execution authority by itself.
