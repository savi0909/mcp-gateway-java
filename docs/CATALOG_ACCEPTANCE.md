# Catalog v1.3 acceptance evidence

Date: 2026-10-08. Scope: local UC-01 ordinary internal reads. This is not complete
discovery/catalog-domain acceptance. [Source v1.3](requirements/Enterprise_MCP_Discovery_Catalog_Requirements_v1.3.md)
retains its finalized requirements and original unchecked checklist. Source
`AC-CAT-01`, `AC-GOV-01`, `AC-DIS-01`, `AC-VER-01` are unchanged. Source E2E-01–12
are referenced here as **CAT-E2E-01–12**, separate from foundation AC and identity
IA-AC identifiers. Evidence is independent mock/SDK behavior, not live integration.

## Domain acceptance

| Source ID | State | Evidence and remaining scope |
| --- | --- | --- |
| AC-CAT-01 | Partial | Publishing v2 creates no membership; authorized owner removal is independent of another catalog; scoped owner requests are implemented. Full ordinary add/activate and unauthorized-owner catalog matrix remain to expand. |
| AC-GOV-01 | Partial | Sensitive request remains pending; sensitive execution is disabled. Positive separate approval, withdrawal and reassessment need the later approval increment. |
| AC-DIS-01 | Partial | Two users of one app see different ordinary tools; direct hidden call fails; current context checked each invocation. Sensitive authorized-positive/workload paths are later. |
| AC-VER-01 | Partial | Publishing v2 leaves v1 membership; retiring v1 denies without substitution; fingerprint mismatch denies. Migration approval/compatibility classification and retirement notice policy remain later. |

## End-to-end checklist

| Tracker ID (source E2E suffix) | State | Executed evidence / remaining requirement |
| --- | --- | --- |
| CAT-E2E-01 | Partial | `scopesAuditAccessAndPreservesPinnedContracts`: publication adds no catalog entry. Ordinary explicit addition lifecycle needs broader end-to-end coverage. |
| CAT-E2E-02 | Partial | `currentGrantIntersectionDelegationExpiryAndSensitiveMembershipAreEnforced`: sensitive membership stays PENDING; no sensitive execution enabled. Positive independent approval is later. |
| CAT-E2E-03 | Verified for ordinary reads | `filtersPerUserAndEnforcesDirectCallsWithIsolatedCredentialsAndDurableAudit`: same app, different users receive one/two eligible tools. |
| CAT-E2E-04 | Verified for ordinary reads | Same test and `sessionOwnershipAndUntrustedMetadataCannotChangeAuthority`: constructed hidden, cross-resource and forged-context calls produce errors with no protected output or requested read. |
| CAT-E2E-05 | Verified for local pinning | `scopesAuditAccessAndPreservesPinnedContracts`: v2 publication does not alter v1 membership. |
| CAT-E2E-06 | Verified for local catalogs | `membershipRemovalIsImmediateAndDoesNotAffectOtherCatalogs`: remove A, B still executes once with its own credentials. |
| CAT-E2E-07 | Partial | Local membership/delegation revocation and contract retirement deny on existing sessions; broader suspended-publication matrix/distributed propagation remains later. |
| CAT-E2E-08 | Pending | Third-party execution and positive provider approval are disabled. |
| CAT-E2E-09 | Partial | Fingerprint mismatch denies; immutable new contract registration does not modify old membership. Compatibility classification and migration workflow remain later. |
| CAT-E2E-10 | Partial | Membership request and revision changes audited, including before/after. No sensitive approval record exists yet. |
| CAT-E2E-11 | Pending | Workload execution disabled. |
| CAT-E2E-12 | Verified for local pinning | Retired v1 returns sanitized ACCESS_DENIED/not_attempted and never selects v2. Migration/unavailability recovery is a future contract. |

Exact final command: `.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' verify`.
Counts and final result are in WORKLOG; use final Maven summaries rather than
historical retained reports from focused IT runs under Surefire. Relevant source:
[Uc01AdmissionIT](../src/test/java/dev/mcp/gateway/admission/Uc01AdmissionIT.java),
[boundary tests](../src/test/java/dev/mcp/gateway/admission/AdmissionBoundaryTest.java),
[policy cache tests](../src/test/java/dev/mcp/gateway/admission/VerifiedPolicyCacheTest.java).
