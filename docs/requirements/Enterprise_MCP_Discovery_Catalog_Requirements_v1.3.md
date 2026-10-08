# Enterprise MCP Platform — Discovery & Catalog Requirements v1.3

Requirements baseline finalized. Supplied by the project owner in conversation
on 2026-10-08; transcribed into Markdown tables. Requirements are defined;
implementation compliance has not yet been tested by this document.

The four decisions are now fixed for the discovery and catalog domain. This
specification defines required behavior, authorization boundaries, lifecycle rules
and testable acceptance criteria. It does not prescribe architecture or implementation.

## 1. Confirmed product decisions

| ID | Decision | Mandatory behavior |
| --- | --- | --- |
| DEC-01 | Explicit catalog membership | Application owners must explicitly request every capability added to their catalog |
| DEC-02 | Sensitive-tool approval | Sensitive capabilities require separate application-level approval, even if already published enterprise-wide |
| DEC-03 | Per-user discovery filtering | Effective tool discovery depends on both application entitlements and represented-user permissions |
| DEC-04 | Pinned contracts | Application catalogs reference explicitly approved capability contract versions |

These decisions apply to both internally hosted and approved third-party MCP servers.

## 2. Foundational access model

The platform must distinguish five conditions:

1. Registered: The MCP server and capability are known to the enterprise.
2. Published: The capability has passed enterprise-level publication requirements.
3. Entitled: The capability and its approved contract version belong to an application's catalog.
4. Discoverable: The current application and represented user are permitted to see the capability.
5. Invocable: The requested operation is permitted under current authorization and contextual policies.

These conditions are related but not interchangeable. For example, a refund tool
may be published and included in a customer-support application's catalog, but
remain invisible and non-invocable to an employee without refund authority.

## 3. Finalized functional requirements

### A. Explicit catalog membership

| ID | Functional requirement | Priority |
| --- | --- | --- |
| CAT-001 | Every application catalog must have a unique identity, accountable owner, and environment | P0 |
| CAT-002 | An authorized application owner must explicitly select capabilities for catalog membership | P0 |
| CAT-003 | Enterprise publication must not automatically grant application membership | P0 |
| CAT-004 | Every membership must identify the capability and its approved contract version | P0 |
| CAT-005 | Application owners must be able to request additions and removals independently | P0 |
| CAT-006 | Membership changes must be authorized, attributable, and auditable | P0 |
| CAT-007 | A capability may belong to multiple application catalogs without sharing their access permissions | P0 |
| CAT-008 | Removing a capability from one catalog must not affect other catalogs | P0 |

**AC-CAT-01.** Given a newly published `payments.getPayment` capability:

- It does not automatically appear in the Support Agent catalog.
- An authorized Support Agent owner can explicitly request its inclusion.
- Once approved where required and activated, the capability becomes eligible for discovery.
- Other applications remain unaffected.
- An unauthorized application owner cannot modify the catalog.

### B. Sensitive-tool membership approval

| ID | Functional requirement | Priority |
| --- | --- | --- |
| GOV-001 | Every published capability must have an enterprise risk classification | P0 |
| GOV-002 | Sensitive capabilities must require application-specific membership approval | P0 |
| GOV-003 | Enterprise publication approval must not substitute for application-level approval | P0 |
| GOV-004 | An approval must identify the application, capability, approved contract, permitted purpose, and approver | P0 |
| GOV-005 | An approval must not grant permissions broader than the enterprise publication policy | P0 |
| GOV-006 | Membership must remain inactive while mandatory approval is pending | P0 |
| GOV-007 | Rejected or withdrawn approvals must prevent activation or continued use | P0 |
| GOV-008 | Material changes to the capability's risk classification or permitted use must trigger reassessment | P0 |
| GOV-009 | Approval decisions and reasons must be auditable | P0 |

**AC-GOV-01.** Given `payments.refundPayment` is classified as sensitive:

- The Payments team publishes the tool after enterprise approval.
- The Support Agent owner requests catalog membership.
- The request remains pending until an authorized approver approves it.
- The tool is not discoverable or invocable by that application while approval is pending.
- Approval for the Support Agent does not grant access to the Risk Agent.
- Withdrawal of approval prevents subsequent invocations within the applicable revocation objective.

### C. Per-user discovery filtering

| ID | Functional requirement | Priority |
| --- | --- | --- |
| DIS-001 | Discovery must identify and authorize the calling application | P0 |
| DIS-002 | Where an application acts for a user, discovery must evaluate the represented user's verified identity and permissions | P0 |
| DIS-003 | The effective catalog must contain only published, entitled, active, and discoverable capabilities | P0 |
| DIS-004 | Unauthorized capabilities must be excluded from ordinary discovery responses | P0 |
| DIS-005 | Different users of the same application may receive different effective catalogs | P0 |
| DIS-006 | An application operating without a represented user must be evaluated using its authorized workload identity and applicable policies | P0 |
| DIS-007 | Discovery must not disclose confidential metadata outside the caller's permissions | P0 |
| DIS-008 | Every invocation must be independently authorized, regardless of previous discovery results | P0 |
| DIS-009 | Changes in user permissions must affect subsequent authorization decisions within the defined enforcement window | P0 |

**AC-DIS-01.** Two employees use the same Support Agent application:

| Tool | Employee A: Support Specialist | Employee B: Refund Specialist |
| --- | --- | --- |
| orders.getOrder | Visible | Visible |
| payments.getPayment | Visible | Visible |
| payments.refundPayment | Hidden | Visible |
| iam.disableAccount | Hidden | Hidden |

This example assumes the three support-related tools have required application
entitlements and approvals. The test passes when both employees receive catalogs
consistent with their permissions; Employee A cannot invoke the refund tool using
a manually constructed request; Employee B can invoke it only when invocation
policies also permit it; permission changes are enforced within the agreed window.
User-aware filtering must never become the sole authorization control. Tool
visibility and execution permission remain separate.

### D. Pinned capability contracts

| ID | Functional requirement | Priority |
| --- | --- | --- |
| VER-001 | Every published capability contract must have an identifiable version | P0 |
| VER-002 | Application membership must reference an explicitly approved contract version | P0 |
| VER-003 | A new upstream contract version must not automatically replace the application's approved version | P0 |
| VER-004 | Application owners must be informed when newer versions become available | P1 |
| VER-005 | Contract changes must be classified for compatibility and security impact | P0 |
| VER-006 | Materially incompatible changes must require explicit application migration or reapproval | P0 |
| VER-007 | Previously approved contracts must not silently change their meaning or required permissions | P0 |
| VER-008 | Contract retirement must follow an established notice and dependency-management policy | P0 |
| VER-009 | The platform must reject invocation of an approved contract version that is no longer safely available | P0 |

**AC-VER-01.** Given an application is approved for refund contract v1:

1. The Payments team publishes v2.
2. Approved membership remains pinned to v1.
3. The application does not automatically receive v2.
4. An authorized owner requests migration to v2.
5. Required compatibility, security and approval conditions are satisfied before activation.
6. If v1 is retired or unavailable, the platform must not silently substitute v2.

A pinned contract does not guarantee indefinite availability. It guarantees that
contract changes cannot silently alter the application's approved behavior.

## 4. Cross-cutting lifecycle requirements

| ID | Requirement | Acceptance condition |
| --- | --- | --- |
| LIFE-001 | Catalog membership must have an explicit lifecycle | Requested, pending approval where required, active, suspended, removed or rejected states are distinguishable |
| LIFE-002 | Publication revocation overrides membership | An unpublished or suspended capability cannot remain invocable because of an existing membership |
| LIFE-003 | User authorization overrides catalog visibility | An entitled application cannot bypass the represented user's permissions |
| LIFE-004 | Membership removal must be enforceable | Cached tool identifiers cannot bypass removal |
| LIFE-005 | Capability retirement must identify affected applications | Owners can determine which catalogs depend on a retiring contract |
| LIFE-006 | Catalog revisions must be auditable | Each effective membership change has an attributable actor, reason and revision |
| LIFE-007 | External-server approval remains independently enforceable | Revoking third-party approval disables affected capabilities regardless of catalog membership |
| LIFE-008 | Revocation must have measurable semantics | New invocations are denied within the agreed enforcement window; in-flight behavior is documented |

## 5. End-to-end enterprise acceptance tests

Source checklist: **0/12 verified**. Use this checklist later during validation.
Requirements are defined; implementation compliance has not yet been tested.
Keep evidence in a separate tracker; this source baseline does not change.

- [ ] E2E-01 — Explicit membership: published tools appear only after explicit selection and activation.
- [ ] E2E-02 — Sensitive approval: pending approval prevents discovery and invocation.
- [ ] E2E-03 — Per-user filtering: two users of one application receive different eligible tools.
- [ ] E2E-04 — Authorization enforcement: bypassing discovery does not permit unauthorized invocation.
- [ ] E2E-05 — Contract pinning: new publication does not silently change approved contracts.
- [ ] E2E-06 — Catalog isolation: removal from Application A does not affect Application B.
- [ ] E2E-07 — Revocation: suspended capability excluded from discovery and denied within the enforcement window.
- [ ] E2E-08 — Third-party withdrawal: withdrawing approval invalidates affected capabilities across catalogs.
- [ ] E2E-09 — Schema incompatibility: incompatible changes cannot silently replace pinned contracts.
- [ ] E2E-10 — Audit reconstruction: reconstruct approver, sensitive membership contract and activation time.
- [ ] E2E-11 — Workload identity: machine agents receive only workload-authorized capabilities.
- [ ] E2E-12 — Retired contract: defined unavailability outcome rather than an unapproved replacement.

## 6. Final access decision rule

Permit invocation only when **every applicable condition holds**: published and
active capability; explicit application membership for the approved contract;
required sensitive-tool approval; authorized application identity; authorized
represented user when applicable; satisfied invocation-specific policies and
external-provider restrictions.

## 7. Requirements status

| Area | Status |
| --- | --- |
| Product scope and consumers | Finalized |
| Internal and third-party MCP server support | Finalized |
| Hybrid publication governance | Finalized |
| Centralized discovery + application catalogs | Finalized |
| Explicit catalog membership | Finalized |
| Sensitive-tool application approval | Finalized |
| Per-user discovery filtering | Finalized |
| Pinned tool contracts | Finalized |
| Catalog lifecycle and access invariants | Baseline defined |
| Revocation timing and in-flight semantics | Pending measurable policy |
| Contract compatibility and deprecation notice periods | Pending policy |
| Risk classification criteria and approval authority | Pending policy |
